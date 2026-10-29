package net.torosaba.core.module.textbeacon

import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID

/** 登録済みの Beacon と、その検索用の表。data.yml と同じ形で読み書きする */
internal class BeaconRegistry {
    val beacons = HashMap<UUID, Beacon>()
    /** 看板の座標 → Beacon ID */
    val signs = HashMap<BlockKey, UUID>()
    /** ワールド名 → 圧縮座標 → Beacon ID */
    private val triggers = HashMap<String, HashMap<Long, UUID>>()
    /** /tb off にしたプレイヤー */
    val muted = HashSet<UUID>()

    fun clear() {
        beacons.clear()
        signs.clear()
        triggers.clear()
        muted.clear()
    }

    fun add(beacon: Beacon) {
        signs[beacon.sign] = beacon.id
        beacon.blocks.forEach { putTrigger(it, beacon.id) }
        beacons[beacon.id] = beacon
    }

    fun remove(id: UUID): Beacon? {
        val b = beacons.remove(id) ?: return null
        signs.remove(b.sign)
        b.blocks.forEach { removeTrigger(it) }
        return b
    }

    fun trigger(world: String, x: Int, y: Int, z: Int): UUID? = triggers[world]?.get(pack(x, y, z))

    private fun putTrigger(key: BlockKey, id: UUID) {
        triggers.getOrPut(key.world) { HashMap() }[pack(key.x, key.y, key.z)] = id
    }

    private fun removeTrigger(key: BlockKey) {
        val map = triggers[key.world] ?: return
        map.remove(pack(key.x, key.y, key.z))
        if (map.isEmpty()) triggers.remove(key.world)
    }

    fun save(): YamlConfiguration {
        val yaml = YamlConfiguration()
        yaml.set("settings.mutedPlayers", muted.map { it.toString() }.sorted())
        for (b in beacons.values) {
            val path = "beacons.${b.id}"
            yaml.set("$path.world", b.world)
            yaml.set("$path.msg", b.message)
            yaml.set("$path.owner.uuid", b.owner?.toString())
            yaml.set("$path.owner.name", b.ownerName)
            yaml.set("$path.sign.x", b.sign.x)
            yaml.set("$path.sign.y", b.sign.y)
            yaml.set("$path.sign.z", b.sign.z)
            yaml.set("$path.blocks", b.blocks.map { "${it.x},${it.y},${it.z}" })
        }
        return yaml
    }

    fun load(yaml: YamlConfiguration, warn: (String) -> Unit) {
        for (s in yaml.getStringList("settings.mutedPlayers")) {
            parseUuid(s)?.let { muted += it } ?: warn("無効なプレイヤー UUID を読み飛ばしました: $s")
        }
        val section = yaml.getConfigurationSection("beacons") ?: return
        for (key in section.getKeys(false)) {
            val id = parseUuid(key)
            if (id == null) {
                warn("data.yml の Beacon UUID が無効です: $key")
                continue
            }
            val path = "beacons.$key"
            val world = yaml.getString("$path.world")
            if (world.isNullOrBlank()) {
                warn("ワールドが無いため Beacon を読み飛ばしました: $key")
                continue
            }
            val sign = BlockKey(world, yaml.getInt("$path.sign.x"), yaml.getInt("$path.sign.y"), yaml.getInt("$path.sign.z"))
            val ownerRaw = yaml.getString("$path.owner.uuid")
            val owner = if (ownerRaw.isNullOrBlank()) null else parseUuid(ownerRaw).also {
                if (it == null) warn("Beacon $key の所有者 UUID が無効です: $ownerRaw")
            }
            val beacon = Beacon(id, sign, yaml.getString("$path.msg", "")!!, owner, yaml.getString("$path.owner.name"))
            for (s in yaml.getStringList("$path.blocks")) {
                val block = parseBlock(world, s)
                if (block == null) warn("Beacon $key の無効なブロックを読み飛ばしました: $s")
                else beacon.blocks += block
            }
            add(beacon)
        }
    }

    companion object {
        fun parseUuid(s: String): UUID? = try {
            UUID.fromString(s)
        } catch (_: IllegalArgumentException) {
            null
        }

        /** `x,y,z` を読む */
        fun parseBlock(world: String, s: String): BlockKey? {
            val sp = s.split(",")
            if (sp.size != 3) return null
            val (x, y, z) = sp.map { it.toIntOrNull() ?: return null }
            return BlockKey(world, x, y, z)
        }
    }
}
