package net.torosaba.core.module.textbeacon

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/** /tb */
internal class BeaconCommand(private val module: TextBeaconModule) {
    private val registry get() = module.registry

    fun execute(sender: CommandSender, label: String, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            if (sender.hasPermission("textbeacon.create")) {
                sender.sendMessage("§e/tb <message>")
                sender.sendMessage("§7改行は \\n で指定できます")
            }
            if (sender is Player) {
                sender.sendMessage("§e/tb on")
                sender.sendMessage("§e/tb off")
            }
            if (sender.hasPermission("textbeacon.admin")) {
                sender.sendMessage("§e/tb list")
                sender.sendMessage("§e/tb list owner:<name>")
                sender.sendMessage("§e/tb info <uuid>")
                sender.sendMessage("§e/tb remove <uuid>")
                sender.sendMessage("§e/tb show")
            }
            return true
        }

        when (args[0].lowercase()) {
            "on", "off" -> {
                if (sender !is Player) return playerOnly(sender)
                val on = args[0].equals("on", ignoreCase = true)
                if (on) registry.muted.remove(sender.uniqueId) else registry.muted.add(sender.uniqueId)
                module.inside.remove(sender.uniqueId)
                sender.sendMessage(if (on) "§aTextBeaconの受信をONにしました" else "§7TextBeaconの受信をOFFにしました")
                module.scheduleSave()
            }
            "list" -> if (admin(sender)) list(sender, args)
            "info" -> if (admin(sender)) info(sender, args)
            "remove" -> if (admin(sender)) {
                val id = beaconId(sender, args, "/tb remove <uuid>") ?: return true
                if (!module.removeBeacon(id, true)) {
                    sender.sendMessage("§cそのIDのBeaconは見つかりませんでした")
                } else {
                    sender.sendMessage("§eTextBeaconを削除しました: $id")
                }
            }
            "show" -> if (admin(sender)) {
                if (sender !is Player) return playerOnly(sender)
                if (module.debugViewers.remove(sender.uniqueId)) {
                    sender.sendMessage("§7TextBeaconの可視化:OFF")
                } else {
                    module.debugViewers += sender.uniqueId
                    sender.sendMessage("§aTextBeaconの可視化:ON")
                }
            }
            else -> giveContentSign(sender, args.joinToString(" ").trim())
        }
        return true
    }

    private fun playerOnly(sender: CommandSender): Boolean {
        sender.sendMessage("§cこのコマンドはプレイヤーのみ実行できます")
        return true
    }

    private fun admin(sender: CommandSender): Boolean {
        if (sender.hasPermission("textbeacon.admin")) return true
        sender.sendMessage("§cこの操作を行う権限がありません (textbeacon.admin)")
        return false
    }

    private fun beaconId(sender: CommandSender, args: Array<String>, usage: String): UUID? {
        if (args.size < 2) {
            sender.sendMessage("§c使い方: $usage")
            return null
        }
        return BeaconRegistry.parseUuid(args[1]) ?: run {
            sender.sendMessage("§cUUIDの形式が正しくありません")
            null
        }
    }

    private fun list(sender: CommandSender, args: Array<String>) {
        if (registry.beacons.isEmpty()) {
            sender.sendMessage("§7登録されているBeaconはありません")
            return
        }
        var ownerFilter: String? = null
        if (args.size >= 2) {
            val arg = args[1]
            if (!arg.lowercase().startsWith("owner:")) {
                sender.sendMessage("§c使い方: /tb list [owner:<name>]")
                return
            }
            ownerFilter = arg.substring("owner:".length).trim()
            if (ownerFilter.isEmpty()) {
                sender.sendMessage("§cowner名を指定してください (例: /tb list owner:Steve)")
                return
            }
        }

        var list = registry.beacons.values.filter { ownerFilter == null || ownerFilter.equals(module.resolveOwnerName(it), ignoreCase = true) }
        if (sender is Player) {
            // プレイヤーには今いるワールドの近い順に 10 件
            val loc = sender.location
            list = list.filter { it.world == loc.world.name }
                .sortedBy { distanceSquared(loc, it.sign) }
                .take(10)
        }
        if (list.isEmpty()) {
            sender.sendMessage("§7条件に一致するBeaconは見つかりませんでした")
            return
        }

        val titleBase = if (ownerFilter == null) "§aTextBeacon一覧" else "§aTextBeacon一覧(owner:$ownerFilter)"
        sender.sendMessage(
            if (sender is Player) "$titleBase §7(現在ワールドの最寄り10件) §f${list.size}件"
            else "$titleBase: ${list.size}件"
        )
        for (b in list) {
            sender.sendMessage(
                "§f${b.id} §7[${b.world} ${b.sign.x},${b.sign.y},${b.sign.z}] §bowner=${module.resolveOwnerName(b)} §bblocks=${b.blocks.size}"
            )
        }
    }

    private fun info(sender: CommandSender, args: Array<String>) {
        val id = beaconId(sender, args, "/tb info <uuid>") ?: return
        val b = registry.beacons[id]
        if (b == null) {
            sender.sendMessage("§cそのIDのBeaconは見つかりませんでした")
            return
        }
        sender.sendMessage("§aID: §f${b.id}")
        sender.sendMessage("§aWorld: §f${b.world}")
        sender.sendMessage("§aSign: §f${b.sign.x},${b.sign.y},${b.sign.z}")
        sender.sendMessage("§aOwner: §f${module.resolveOwnerName(b)} §7(${b.owner ?: "unknown"})")
        sender.sendMessage("§aBlocks: §f${b.blocks.size}")
        sender.sendMessage("§aMessage:")
        b.message.split("\n").forEach { sender.sendMessage("§f$it") }
    }

    /** メッセージ入りの看板を渡す */
    private fun giveContentSign(sender: CommandSender, content: String) {
        if (!sender.hasPermission("textbeacon.create")) {
            sender.sendMessage("§cこの操作を行う権限がありません (textbeacon.create)")
            return
        }
        if (sender !is Player) {
            playerOnly(sender)
            return
        }
        if (content.isBlank()) {
            sender.sendMessage("§c使い方: /tb <メッセージ>")
            sender.sendMessage("§7&1- &fや &#55ffff の色コードが使えます")
            sender.sendMessage("§7\\nで改行できます")
            return
        }
        if (content.length > 256) {
            sender.sendMessage("§cメッセージは256文字以内にしてください")
            return
        }

        val item = ItemStack(Material.OAK_SIGN, 1)
        val meta = item.itemMeta as? BlockStateMeta
        val state = meta?.blockState as? Sign
        if (meta == null || state == null) {
            sender.sendMessage("§c看板を作成できませんでした")
            return
        }
        state.getSide(Side.FRONT).writeBeacon(splitMessageForSign(content.trim()))
        meta.blockState = state
        meta.persistentDataContainer.set(module.itemMessageKey, PersistentDataType.STRING, content.trim())
        item.itemMeta = meta

        sender.inventory.addItem(item).values.forEach { sender.world.dropItemNaturally(sender.location, it) }
        sender.sendMessage("§a取得した看板を設置してください！")
    }

    fun tabComplete(sender: CommandSender, alias: String, args: Array<String>): List<String> {
        if (args.size == 1) {
            val roots = mutableListOf<String>()
            if (sender is Player) roots += listOf("on", "off")
            if (sender.hasPermission("textbeacon.admin")) roots += listOf("list", "info", "remove", "show")
            return roots
        }
        if (!sender.hasPermission("textbeacon.admin") || args.size != 2) return emptyList()

        return when (args[0].lowercase()) {
            "list" -> {
                val prefix = args[1].lowercase()
                if (!"owner:".startsWith(prefix) && !prefix.startsWith("owner:")) return listOf("owner:")
                val namePrefix = if (prefix.startsWith("owner:")) prefix.substring("owner:".length) else ""
                registry.beacons.values.asSequence()
                    .map { module.resolveOwnerName(it) }
                    .filter { it != "(unknown)" && it.lowercase().startsWith(namePrefix) }
                    .distinct()
                    .sortedWith(String.CASE_INSENSITIVE_ORDER)
                    .take(20)
                    .map { "owner:$it" }
                    .toList()
                    .ifEmpty { listOf("owner:") }
            }
            "info", "remove" -> {
                val prefix = args[1].lowercase()
                registry.beacons.keys.map { it.toString() }.filter { it.startsWith(prefix) }.take(20)
            }
            else -> emptyList()
        }
    }

    private fun distanceSquared(origin: Location, key: BlockKey): Double {
        val dx = key.x + 0.5 - origin.x
        val dy = key.y + 0.5 - origin.y
        val dz = key.z + 0.5 - origin.z
        return dx * dx + dy * dy + dz * dz
    }
}
