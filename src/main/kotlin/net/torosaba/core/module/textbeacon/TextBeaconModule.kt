package net.torosaba.core.module.textbeacon

import net.torosaba.core.Module
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Effect
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.block.Block
import org.bukkit.block.Sign
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.permissions.PermissionDefault
import org.bukkit.util.BoundingBox
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.logging.Level
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** 旧 TextBeacon。看板と空色のガラスで「入るとメッセージが出る範囲」を作る */
class TextBeaconModule : Module("text-beacon", "TextBeacon") {
    internal val registry = BeaconRegistry()
    /** プレイヤー → いま中にいる Beacon */
    internal val inside = HashMap<UUID, MutableSet<UUID>>()
    internal val debugViewers = HashSet<UUID>()

    /** 看板アイテムに入れるメッセージ。元のプラグインで作ったアイテムも読めるよう名前空間はそのまま */
    internal val itemMessageKey = NamespacedKey("textbeacon", "tb-message")

    private val file: File get() = File(dataFolder, "data.yml")
    private var saveScheduled = false

    override fun onEnable() {
        registry.clear()
        inside.clear()
        debugViewers.clear()
        saveScheduled = false

        permission("textbeacon.use", PermissionDefault.TRUE, "TextBeaconのメッセージを受け取る権限")
        permission("textbeacon.create", PermissionDefault.OP, "TextBeacon看板を作成する権限")
        permission("textbeacon.remove", PermissionDefault.OP, "TextBeaconを削除する権限")
        permission("textbeacon.admin", PermissionDefault.OP, "TextBeaconの範囲を可視化を利用する権限")

        dataFolder.mkdirs()
        if (file.exists()) registry.load(YamlConfiguration.loadConfiguration(file)) { logger.warning(it) }
        val removed = pruneMissingSignBeacons()
        if (removed > 0) {
            logger.info("看板が無くなっていた Beacon を $removed 件削除しました")
            saveNow()
        }

        listen(BeaconListener(this))
        val cmd = BeaconCommand(this)
        command(
            "tb",
            description = "TextBeaconの管理コマンド",
            usage = "/tb <message|on|off> | /tb <list [owner:<name>]|info|remove|show>",
            tab = cmd::tabComplete,
            executor = cmd::execute,
        )
        runTimer(20L, 20L) { drawDebug() }
    }

    override fun onDisable() {
        debugViewers.clear()
        saveNow()
        inside.clear()
    }

    // ---- 登録と削除 ----

    /** [base] の看板の近くにある空色のガラスを Beacon にする */
    internal fun tryRegisterBeacon(player: Player, base: Location, message: String): Boolean {
        val msg = colorize(message)
        if (msg.isBlank()) {
            player.sendMessage("§c看板の2〜4行目にメッセージを入力してください")
            return false
        }
        if (msg.length > 256) {
            player.sendMessage("§cメッセージは256文字以内にしてください")
            return false
        }
        val nearest = findNearestGlass(base, SEARCH_RADIUS)
        if (nearest == null) {
            player.sendMessage("§c半径5ブロック以内に空色のガラスが見つかりませんでした")
            return false
        }
        val world = nearest.world
        val glass = collectConnected(intArrayOf(nearest.x, nearest.y, nearest.z)) { x, y, z ->
            world.getBlockAt(x, y, z).type == GLASS
        }
        if (glass == null || !isWithinCube(glass)) {
            player.sendMessage("§cガラスの範囲は5x5x5以内にしてください")
            return false
        }

        val beacon = Beacon(UUID.randomUUID(), BlockKey(base), msg, player.uniqueId, player.name)
        for ((x, y, z) in glass) {
            beacon.blocks += BlockKey(world.name, x, y, z)
            val block = world.getBlockAt(x, y, z)
            playBreakEffect(block)
            block.type = Material.AIR
        }
        registry.add(beacon)
        player.sendMessage("§aBeaconを設置しました: ${glass.size}ブロック")
        scheduleSave()
        return true
    }

    /** Beacon を消す。[destroyWorldBlocks] なら看板と範囲のブロックも壊す */
    internal fun removeBeacon(id: UUID, destroyWorldBlocks: Boolean): Boolean {
        val b = registry.remove(id) ?: return false
        if (destroyWorldBlocks) destroyBeaconBlocks(b)
        inside.values.forEach { it.remove(id) }
        scheduleSave()
        return true
    }

    private fun destroyBeaconBlocks(b: Beacon) {
        val world = Bukkit.getWorld(b.world) ?: return
        val sign = world.getBlockAt(b.sign.x, b.sign.y, b.sign.z)
        if (sign.type != Material.AIR) {
            playBreakEffect(sign)
            sign.type = Material.AIR
        }
        for (bk in b.blocks) {
            val block = world.getBlockAt(bk.x, bk.y, bk.z)
            if (block.type == Material.AIR) continue
            playBreakEffect(block)
            block.type = Material.AIR
        }
    }

    private fun playBreakEffect(block: Block) {
        block.world.playEffect(block.location, Effect.DESTROY_BLOCK_WITH_SOUND, block.blockData)
    }

    /** 起動時、看板やワールドが無くなっている Beacon を消す */
    private fun pruneMissingSignBeacons(): Int {
        val invalid = registry.beacons.values.filter { b ->
            val world = Bukkit.getWorld(b.world) ?: return@filter true
            world.getBlockAt(b.sign.x, b.sign.y, b.sign.z).getState(false) !is Sign
        }
        invalid.forEach { b ->
            registry.remove(b.id)
            inside.values.forEach { it.remove(b.id) }
        }
        return invalid.size
    }

    // ---- ガラスの検出・入場判定 ----

    private fun findNearestGlass(center: Location, radius: Int): Block? {
        val w = center.world ?: return null
        val cx = center.blockX
        val cy = center.blockY
        val cz = center.blockZ
        var bestDist = Int.MAX_VALUE
        var best: Block? = null
        for (x in -radius..radius) for (y in -radius..radius) for (z in -radius..radius) {
            val b = w.getBlockAt(cx + x, cy + y, cz + z)
            if (b.type != GLASS) continue
            val dist = x * x + y * y + z * z
            if (dist < bestDist) {
                bestDist = dist
                best = b
            }
        }
        return best
    }

    /** 移動先 [to] でプレイヤーの体 (と頭の上 1 ブロック) が触れている Beacon */
    internal fun triggeredBeacon(player: Player, to: Location): UUID? {
        val current = player.location
        val box: BoundingBox = player.boundingBox.shift(to.x - current.x, to.y - current.y, to.z - current.z)
        val world = to.world.name
        val maxY = floor(box.maxY).toInt() + UPPER_BODY_BLOCK_OFFSET
        for (x in floor(box.minX).toInt()..floor(box.maxX).toInt()) {
            for (y in floor(box.minY).toInt()..maxY) {
                for (z in floor(box.minZ).toInt()..floor(box.maxZ).toInt()) {
                    registry.trigger(world, x, y, z)?.let { return it }
                }
            }
        }
        return null
    }

    internal fun resolveOwnerName(b: Beacon): String {
        b.ownerName?.takeIf { it.isNotBlank() }?.let { return it }
        val owner = b.owner ?: return "(unknown)"
        val name = Bukkit.getOfflinePlayer(owner).name
        if (!name.isNullOrBlank()) {
            b.ownerName = name
            return name
        }
        return owner.toString()
    }

    // ---- 可視化 (/tb show) ----

    private fun drawDebug() {
        if (debugViewers.isEmpty() || registry.beacons.isEmpty()) return
        val it = debugViewers.iterator()
        while (it.hasNext()) {
            val viewer = Bukkit.getPlayer(it.next())
            if (viewer == null || !viewer.isOnline || !viewer.hasPermission("textbeacon.admin")) {
                it.remove()
                continue
            }
            var count = 0
            val world = viewer.world
            for (b in registry.beacons.values) {
                if (b.world != world.name) continue
                val signLoc = Location(world, b.sign.x + 0.5, b.sign.y + 1.1, b.sign.z + 0.5)
                count += debugPoint(viewer, signLoc, DEBUG_PARTICLE_CAP - count)
                if (count >= DEBUG_PARTICLE_CAP) break
                for (bk in b.blocks) {
                    if (count >= DEBUG_PARTICLE_CAP) break
                    val point = Location(world, bk.x + 0.5, bk.y + 0.5, bk.z + 0.5)
                    count += debugPoint(viewer, point, DEBUG_PARTICLE_CAP - count)
                    if (count >= DEBUG_PARTICLE_CAP) break
                    count += debugLine(viewer, signLoc, point, DEBUG_PARTICLE_CAP - count)
                }
                if (count >= DEBUG_PARTICLE_CAP) break
            }
        }
    }

    private fun debugPoint(viewer: Player, loc: Location, budget: Int): Int {
        if (budget <= 0) return 0
        viewer.spawnParticle(Particle.DUST, loc, 1, 0.0, 0.0, 0.0, 0.0, DEBUG_RED_DUST)
        return 1
    }

    private fun debugLine(viewer: Player, from: Location, to: Location, budget: Int): Int {
        if (budget <= 0) return 0
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        val steps = maxOf(1, ceil(sqrt(dx * dx + dy * dy + dz * dz) / DEBUG_LINE_STEP).toInt())
        var spawned = 0
        var i = 0
        while (i <= steps && spawned < budget) {
            val t = i / steps.toDouble()
            viewer.spawnParticle(Particle.DUST, from.x + dx * t, from.y + dy * t, from.z + dz * t, 1, 0.0, 0.0, 0.0, 0.0, DEBUG_RED_DUST)
            spawned++
            i++
        }
        return spawned
    }

    // ---- 保存 (まとめて 5 秒後) ----

    internal fun scheduleSave() {
        if (saveScheduled) return
        saveScheduled = true
        runLater(100L) {
            saveNow()
            saveScheduled = false
        }
    }

    private fun saveNow() {
        try {
            registry.save().save(file)
        } catch (e: IOException) {
            logger.log(Level.SEVERE, "data.yml を保存できませんでした", e)
        }
    }

    internal companion object {
        val GLASS = Material.LIGHT_BLUE_STAINED_GLASS
        const val SEARCH_RADIUS = 5
        const val UPPER_BODY_BLOCK_OFFSET = 1
        const val DEBUG_PARTICLE_CAP = 1000
        const val DEBUG_LINE_STEP = 0.35
        val DEBUG_RED_DUST = Particle.DustOptions(Color.RED, 1.0f)
    }
}
