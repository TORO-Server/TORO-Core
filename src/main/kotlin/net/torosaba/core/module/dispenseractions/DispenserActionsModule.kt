package net.torosaba.core.module.dispenseractions

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.torosaba.core.Module
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockDispenseEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.meta.ItemMeta
import java.util.regex.Pattern

/**
 * 旧 DispenserActions。
 * 説明文 (lore) を書いた音符ブロックを発射すると音を鳴らし、羽根を発射すると音を止める。
 */
class DispenserActionsModule : Module("dispenser-actions", "DispenserActions"), Listener {

    override fun onEnable() {
        listen(this)
    }

    @EventHandler
    fun onDispense(event: BlockDispenseEvent) {
        val block = event.block
        if (block.type != Material.DISPENSER) return
        val item = event.item
        val meta = item.itemMeta ?: return
        val location = block.location
        when (item.type) {
            Material.NOTE_BLOCK -> {
                val play = SoundPlay.read(meta, this)
                if (play.warnText(this) != null) return
                play.run(location)
            }
            Material.FEATHER -> {
                val stop = SoundStop.read(meta, this)
                if (stop.warnText(this) != null) return
                stop.run(location)
            }
            else -> return
        }
        event.isCancelled = true
        stopDispenseSound(location)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        if (event.inventory.type != InventoryType.DISPENSER) return
        val item = event.currentItem ?: return
        val meta = item.itemMeta ?: return
        val warn = when (item.type) {
            Material.NOTE_BLOCK -> SoundPlay.read(meta, this).warnText(this)
            Material.FEATHER -> SoundStop.read(meta, this).warnText(this)
            else -> null
        } ?: return
        event.whoClicked.sendMessage(warn)
    }

    // 近くのプレイヤーに聞こえるディスペンサーの音を消す
    private fun stopDispenseSound(location: Location) {
        for (player in location.getNearbyPlayers(16.0)) {
            runLater(0) { player.stopSound(Sound.BLOCK_DISPENSER_DISPENSE, SoundCategory.BLOCKS) }
        }
    }

    /** lore の各行を、config の見出し (例: "サウンド ") の後ろの値として読む。行数か見出しが合わなければ null */
    private fun readLore(meta: ItemMeta, keys: List<String>): List<String>? {
        val lore = meta.lore() ?: return null
        if (lore.size != keys.size) return null
        val plain = PlainTextComponentSerializer.plainText()
        return keys.mapIndexed { i, key ->
            // 元の String.split と同じ分け方 (見出しは正規表現として扱う)
            val parts = Pattern.compile(key).split(plain.serialize(lore[i]))
            if (parts.size != 2) return null
            parts[1]
        }
    }

    private class SoundPlay(val sound: String?, val volume: Float, val pitch: Float, val category: SoundCategory?) {
        fun warnText(m: DispenserActionsModule): String? {
            val c = m.config
            return when {
                sound == null || volume.isNaN() || pitch.isNaN() -> c.getString("playSound.warnText.NotEnoughSettings")
                volume > c.getDouble("playSound.maxVolume").toFloat() -> c.getString("playSound.warnText.ExceedingMaxVolume")
                pitch > c.getDouble("playSound.maxPitch").toFloat() -> c.getString("playSound.warnText.ExceedingMaxPitch")
                else -> null
            }
        }

        fun run(location: Location) {
            val sound = sound ?: return
            if (category == null) location.world.playSound(location, sound, volume, pitch)
            else location.world.playSound(location, sound, category, volume, pitch)
        }

        companion object {
            fun read(meta: ItemMeta, m: DispenserActionsModule): SoundPlay {
                val keys = listOf("sound", "volume", "pitch", "category").map { m.config.getString("playSound.lore.$it") ?: "" }
                val v = m.readLore(meta, keys) ?: return SoundPlay(null, 0f, 0f, null)
                return SoundPlay(v[0], v[1].toFloatOrNaN(), v[2].toFloatOrNaN(), soundCategory(v[3]))
            }
        }
    }

    private class SoundStop(val sound: String?, val distance: Float, val category: SoundCategory?) {
        fun warnText(m: DispenserActionsModule): String? {
            val c = m.config
            return when {
                sound == null || distance.isNaN() -> c.getString("stopSound.warnText.NotEnoughSettings")
                distance > c.getDouble("stopSound.maxDistance").toFloat() -> c.getString("stopSound.warnText.ExceedingMaxDistance")
                else -> null
            }
        }

        fun run(location: Location) {
            val sound = sound ?: return
            for (player in location.getNearbyPlayers(distance.toDouble())) {
                if (category == null) player.stopSound(sound) else player.stopSound(sound, category)
            }
        }

        companion object {
            fun read(meta: ItemMeta, m: DispenserActionsModule): SoundStop {
                val keys = listOf("sound", "distance", "category").map { m.config.getString("stopSound.lore.$it") ?: "" }
                val v = m.readLore(meta, keys) ?: return SoundStop(null, 0f, null)
                return SoundStop(v[0], v[1].toFloatOrNaN(), soundCategory(v[2]))
            }
        }
    }

    private companion object {
        // 元のプラグインが受け付けていたカテゴリ (BLOCKS は含まない)
        val categories = listOf(
            SoundCategory.MASTER, SoundCategory.MUSIC, SoundCategory.RECORDS, SoundCategory.WEATHER, SoundCategory.HOSTILE,
            SoundCategory.NEUTRAL, SoundCategory.PLAYERS, SoundCategory.AMBIENT, SoundCategory.VOICE,
        )

        fun soundCategory(text: String): SoundCategory? = categories.firstOrNull { it.name.equals(text, ignoreCase = true) }

        // 数値でなければ NaN (設定不足として扱う)
        fun String.toFloatOrNaN(): Float = try {
            java.lang.Float.parseFloat(this)
        } catch (_: NumberFormatException) {
            Float.NaN
        }
    }
}
