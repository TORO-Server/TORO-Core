package net.torosaba.core.module.mapdirty;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.LocalConfiguration;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.event.platform.PlatformReadyEvent;
import com.sk89q.worldedit.event.platform.PlatformsRegisteredEvent;
import com.sk89q.worldedit.extension.platform.AbstractNonPlayerActor;
import com.sk89q.worldedit.extension.platform.Capability;
import com.sk89q.worldedit.extension.platform.Platform;
import com.sk89q.worldedit.extension.platform.Preference;
import com.sk89q.worldedit.function.pattern.BlockPattern;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.SessionKey;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.world.AbstractWorld;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import com.sk89q.worldedit.world.block.BlockType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorldEdit 経路の記録テスト。WorldEdit の実物 (7.4.6) に最小のプラットフォームと
 * ワールド、プレイヤーではないアクター (コンソール相当) を組ませて確かめる。
 * スタンドアロンの再現プログラムで原因を特定したときと同じ構成。
 *
 * 守るべき性質は 2 つ。
 * 1. コンソールのアクターが LocalSession 経由で作った EditSession の編集が、
 *    WORLDEDIT 経路で対象チャンクに記録されること。
 * 2. 編集が 1 件も無いうちに届くコミット (LocalSession.createEditSession が
 *    setReorderMode の中で行うもの) でセッションが確定済みにならず、
 *    後の編集が捨てられないこと。
 */
class WorldEditDirtyHookTest {

    private static boolean platformReady = false;

    @BeforeAll
    static synchronized void setUpWorldEdit() {
        if (platformReady) {
            return;
        }
        WorldEdit we = WorldEdit.getInstance();
        StubPlatform platform = new StubPlatform();
        we.getPlatformManager().register(platform);
        we.getEventBus().post(new PlatformReadyEvent(platform));
        we.getEventBus().post(new PlatformsRegisteredEvent());
        assertNotNull(we.getConfiguration());
        platformReady = true;
    }

    @Test
    void consoleActorEditSessionRecordsChunk() throws Exception {
        FakeWorld world = new FakeWorld();
        DirtyWindow window = new DirtyWindow(60_000L, () -> System.currentTimeMillis());
        WorldEditDirtyHook hook = new WorldEditDirtyHook(window);
        hook.start();
        try {
            LocalSession session = new LocalSession();
            session.setWorldOverride(world);
            ConsoleActor actor = new ConsoleActor();
            EditSession editSession = session.createEditSession(actor);
            int affected = editSession.setBlocks(
                new CuboidRegion(world, BlockVector3.at(2, 64, 2), BlockVector3.at(2, 64, 2)),
                new BlockPattern(FakeWorld.STONE.getDefaultState()));
            assertEquals(1, affected);
            editSession.close();
            // 編集自体はワールドに届いている
            assertTrue(world.blocks.containsKey(BlockVector3.at(2, 64, 2)));
        } finally {
            hook.stop();
        }
        WindowSnapshot snapshot = window.flush();
        assertNotNull(snapshot);
        assertEquals(1, snapshot.count(DirtySource.WORLDEDIT));
        assertEquals(0, snapshot.count(DirtySource.BUKKIT));
        assertEquals(
            Set.of(ChunkMath.INSTANCE.pack(0, 0)),
            snapshot.getWorlds().get("main").get(DirtySource.WORLDEDIT));
    }

    @Test
    void commitBeforeAnyEditDoesNotDiscardLaterEdits() throws Exception {
        FakeWorld world = new FakeWorld();
        DirtyWindow window = new DirtyWindow(60_000L, () -> System.currentTimeMillis());
        WorldEditDirtyHook hook = new WorldEditDirtyHook(window);
        hook.start();
        try {
            ConsoleActor actor = new ConsoleActor();
            EditSessionEvent event = new EditSessionEvent(world, actor, -1, EditSession.Stage.BEFORE_CHANGE);
            event.setExtent(world);
            WorldEdit.getInstance().getEventBus().post(event);
            com.sk89q.worldedit.extent.AbstractDelegateExtent wrapped =
                (com.sk89q.worldedit.extent.AbstractDelegateExtent) event.getExtent();
            assertNotSame(world, wrapped);
            // セッション生成時のコミットを模す。まだ編集は無いので何も出ない
            wrapped.commit();
            assertNull(window.flush());
            // その後の編集は、次のコミットで記録されなければならない
            wrapped.setBlock(BlockVector3.at(35, 64, 35), FakeWorld.STONE.getDefaultState());
            wrapped.commit();
        } finally {
            hook.stop();
        }
        WindowSnapshot snapshot = window.flush();
        assertNotNull(snapshot);
        assertEquals(1, snapshot.count(DirtySource.WORLDEDIT));
        assertEquals(
            Set.of(ChunkMath.INSTANCE.pack(2, 2)),
            snapshot.getWorlds().get("main").get(DirtySource.WORLDEDIT));
    }

    // ---- ここから下は WorldEdit をヘッドレスで動かすための最小の相手役 ----

    static class StubConfig extends LocalConfiguration {
        @Override
        public void load() {
        }
    }

    static class StubPlatform implements Platform {
        private final LocalConfiguration config = new StubConfig();

        @Override
        public com.sk89q.worldedit.util.io.ResourceLoader getResourceLoader() {
            return null;
        }

        @Override
        public com.sk89q.worldedit.util.translation.TranslationManager getTranslationManager() {
            return null;
        }

        @Override
        public com.sk89q.worldedit.world.registry.Registries getRegistries() {
            return com.sk89q.worldedit.world.registry.BundledRegistries.getInstance();
        }

        @Override
        public int getDataVersion() {
            return 0;
        }

        @Override
        public com.sk89q.worldedit.world.DataFixer getDataFixer() {
            return null;
        }

        @Override
        public boolean isValidMobType(String type) {
            return false;
        }

        @Override
        public void reload() {
        }

        @Override
        public int schedule(long delay, long interval, Runnable task) {
            return 0;
        }

        @Override
        public List<? extends com.sk89q.worldedit.world.World> getWorlds() {
            return List.of();
        }

        @Override
        public com.sk89q.worldedit.entity.Player matchPlayer(com.sk89q.worldedit.entity.Player player) {
            return player;
        }

        @Override
        public com.sk89q.worldedit.world.World matchWorld(com.sk89q.worldedit.world.World world) {
            return world;
        }

        @Override
        public void registerCommands(org.enginehub.piston.CommandManager commandManager) {
        }

        @Override
        public void setGameHooksEnabled(boolean enabled) {
        }

        @Override
        public LocalConfiguration getConfiguration() {
            return config;
        }

        @Override
        public String getVersion() {
            return "test";
        }

        @Override
        public String getPlatformName() {
            return "test";
        }

        @Override
        public String getPlatformVersion() {
            return "test";
        }

        @Override
        public Map<Capability, Preference> getCapabilities() {
            Map<Capability, Preference> map = new EnumMap<>(Capability.class);
            for (Capability c : Capability.values()) {
                map.put(c, Preference.NORMAL);
            }
            return map;
        }

        @Override
        public Set<SideEffect> getSupportedSideEffects() {
            return Set.of();
        }

        @Override
        public long getTickCount() {
            return 0;
        }
    }

    static class FakeWorld extends AbstractWorld {
        static final BlockType AIR = new BlockType("minecraft:air");
        static final BlockType STONE = new BlockType("minecraft:stone");
        final Map<BlockVector3, BlockState> blocks = new HashMap<>();

        @Override
        public String getName() {
            return "main";
        }

        @Override
        public String id() {
            return "main";
        }

        @Override
        public <B extends BlockStateHolder<B>> boolean setBlock(BlockVector3 position, B block, SideEffectSet sideEffects) {
            blocks.put(position, block.toImmutableState());
            return true;
        }

        @Override
        public BlockState getBlock(BlockVector3 position) {
            return blocks.getOrDefault(position, AIR.getDefaultState());
        }

        @Override
        public BaseBlock getFullBlock(BlockVector3 position) {
            return getBlock(position).toBaseBlock();
        }

        @Override
        public BlockVector3 getSpawnPosition() {
            return BlockVector3.at(0, 64, 0);
        }

        @Override
        public Set<SideEffect> applySideEffects(BlockVector3 position, BlockState previousType, SideEffectSet sideEffectSet) {
            return Set.of();
        }

        @Override
        public int getBlockLightLevel(BlockVector3 position) {
            return 0;
        }

        @Override
        public boolean clearContainerBlockContents(BlockVector3 position) {
            return false;
        }

        @Override
        public void dropItem(com.sk89q.worldedit.math.Vector3 position, com.sk89q.worldedit.blocks.BaseItemStack item) {
        }

        @Override
        public void simulateBlockMine(BlockVector3 position) {
        }

        @Override
        public boolean generateTree(com.sk89q.worldedit.world.generation.TreeType type, EditSession session, BlockVector3 position) {
            return false;
        }

        @Override
        public com.sk89q.worldedit.world.biome.BiomeType getBiome(BlockVector3 position) {
            return null;
        }

        @Override
        public com.sk89q.worldedit.entity.Entity createEntity(com.sk89q.worldedit.util.Location location, com.sk89q.worldedit.entity.BaseEntity entity) {
            return null;
        }

        @Override
        public List<? extends com.sk89q.worldedit.entity.Entity> getEntities() {
            return List.of();
        }

        @Override
        public List<? extends com.sk89q.worldedit.entity.Entity> getEntities(com.sk89q.worldedit.regions.Region region) {
            return List.of();
        }
    }

    /** プレイヤーではないアクター。サーバーのコンソールからの実行を模す */
    static class ConsoleActor extends AbstractNonPlayerActor {
        private final UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

        @Override
        public UUID getUniqueId() {
            return uuid;
        }

        @Override
        public String getName() {
            return "CONSOLE";
        }

        @Override
        public void printRaw(String message) {
        }

        @Override
        public void print(com.sk89q.worldedit.util.formatting.text.Component component) {
        }

        @Override
        public void print(String message) {
        }

        @Override
        public void printDebug(String message) {
        }

        @Override
        public void printError(String message) {
        }

        @Override
        public String[] getGroups() {
            return new String[0];
        }

        @Override
        public boolean hasPermission(String permission) {
            return true;
        }

        @Override
        public void checkPermission(String permission) {
        }

        @Override
        public Locale getLocale() {
            return Locale.ROOT;
        }

        @Override
        public SessionKey getSessionKey() {
            return new SessionKey() {
                @Override
                public UUID getUniqueId() {
                    return uuid;
                }

                @Override
                public String getName() {
                    return "CONSOLE";
                }

                @Override
                public boolean isActive() {
                    return true;
                }

                @Override
                public boolean isPersistent() {
                    return false;
                }
            };
        }
    }
}
