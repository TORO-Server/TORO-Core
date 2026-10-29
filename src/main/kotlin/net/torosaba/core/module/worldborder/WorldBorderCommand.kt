// WorldBorder (Brettflan, BSD-2-Clause License) の WBCommand と cmd/Cmd*.java を移植したもの
package net.torosaba.core.module.worldborder

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import kotlin.math.ceil

/**
 * `/wborder` (別名 `/wb`)。サブコマンドの名前・引数・権限 (`worldborder.<名前>`) は元と同じ。
 * fill / trim / fillautosave は移植していない (Paper の事前生成の仕組みを使う)。
 */
internal class WorldBorderCommand(private val module: WorldBorderModule) {
    private val settings get() = module.settings

    private class Example(val text: String, val forPlayer: Boolean = true, val forConsole: Boolean = true, val prefix: Boolean = true)

    private class Ctx(val sender: CommandSender, var player: Player?, val params: MutableList<String>, var world: String?, val showStatus: () -> Unit)

    private inner class Sub(
        val name: String,
        val permission: String = name,
        val worldInput: Boolean = false,
        val consoleNeedsWorld: Boolean = true,
        val min: Int = 0,
        val max: Int = 9999,
        examples: List<Example> = emptyList(),
        val help: String? = null,
        val status: ((CommandSender) -> Unit)? = null,
        val run: Ctx.() -> Unit,
    ) {
        val playerExamples = examples.filter { it.forPlayer }.map { formatExample(it, name, forPlayer = true) }
        val consoleExamples = examples.filter { it.forConsole }.map { formatExample(it, name, forPlayer = false) }

        fun sendHelp(sender: CommandSender) {
            (if (sender is Player) playerExamples else consoleExamples).forEach(sender::sendMessage)
            status?.invoke(sender)
            if (!help.isNullOrEmpty()) sender.sendMessage(C_DESC + help)
        }

        fun error(sender: CommandSender, message: String) {
            sender.sendMessage(C_ERR + message)
            sendHelp(sender)
        }
    }

    /** 処理を止めてエラーと使い方を出す */
    private class Stop(message: String) : RuntimeException(message)

    private val subs = LinkedHashMap<String, Sub>()
    private val allPlayerExamples = mutableListOf<String>()
    private val allConsoleExamples = mutableListOf<String>()

    private fun add(sub: Sub) {
        subs[sub.name] = sub
        allPlayerExamples += sub.playerExamples
        allConsoleExamples += sub.consoleExamples
    }

    init {
        registerSubcommands()
    }

    fun register() {
        module.command(
            "wborder",
            aliases = listOf("wb"),
            description = "Primary command for WorldBorder.",
            usage = "",
            tab = { _, _, args -> complete(args) },
        ) { sender, _, args -> execute(sender, args); true }
    }

    // ---- 実行 ----

    private fun execute(sender: CommandSender, args: Array<String>) {
        val player = sender as? Player
        val (params, quoted) = concatenateQuotedWorldName(args)

        // 2 番目がコマンドで 1 番目がコマンドでなければ、1 番目はワールド名
        var world: String? = null
        if (quoted || (params.size > 1 && params[0].lowercase() !in subs && subs[params[1].lowercase()]?.worldInput == true)) {
            world = params[0]
        }
        if (params.isEmpty()) params += "commands"
        var name = (if (world == null) params[0] else params[1]).lowercase()
        params.removeAt(0)
        if (world != null) params.removeAt(0)

        if (name !in subs) {
            var page = if (player == null) 0 else 1
            val number = name.toIntOrNull()
            if (number != null) page = number else sender.sendMessage(C_ERR + "Command not recognized. Showing command list.")
            name = "commands"
            params.add(0, page.toString())
        }
        val sub = subs.getValue(name)

        if (player != null && !player.hasPermission("worldborder.${sub.permission}")) {
            player.sendMessage("You do not have sufficient permissions.")
            return
        }
        if (player == null && sub.worldInput && sub.consoleNeedsWorld && world == null) {
            sub.error(sender, "This command requires a world to be specified if run by the console.")
            return
        }
        if (params.size < sub.min || params.size > sub.max) {
            sub.error(sender, if (sub.max == 0) "This command does not accept any parameters." else "You have not provided a valid number of parameters.")
            return
        }
        try {
            sub.run(Ctx(sender, player, params, world) { sub.status?.invoke(sender) })
        } catch (e: Stop) {
            sub.error(sender, e.message ?: "")
        }
    }

    /** `"my world"` のように引用符で囲んだワールド名を 1 つにまとめる。先頭が引用符なら true も返す */
    private fun concatenateQuotedWorldName(split: Array<String>): Pair<MutableList<String>, Boolean> {
        val args = split.toMutableList()
        val start = args.indexOfFirst { it.startsWith("\"") }
        if (start == -1) return args to false
        val first = args[start]
        if (first.length > 1 && first.endsWith("\"")) {
            args[start] = first.substring(1, first.length - 1)
            return args to (start == 0)
        }
        val end = (start + 1 until args.size).firstOrNull { args[it].endsWith("\"") } ?: return args to false
        val joined = args.subList(start, end + 1).joinToString(" ")
        repeat(end - start) { args.removeAt(start + 1) }
        args[start] = joined.substring(1, joined.length - 1)
        return args to (start == 0)
    }

    private fun complete(args: Array<String>): List<String> {
        val names = subs.keys.filter { it != "commands" }
        val worlds = Bukkit.getWorlds().map { it.name }
        return when (args.size) {
            1 -> names + worlds
            2 -> when {
                args[0].lowercase() in subs -> when (args[0].lowercase()) {
                    "shape" -> listOf("round", "square", "elliptic", "rectangular")
                    "wshape", "wrap" -> worlds
                    "bypass" -> Bukkit.getOnlinePlayers().map { it.name }
                    "help" -> names
                    else -> if (args[0].lowercase() in ON_OFF_COMMANDS) listOf("on", "off") else emptyList()
                }
                else -> subs.values.filter { it.worldInput }.map { it.name }
            }
            3 -> when (args[0].lowercase()) {
                "wshape" -> listOf("round", "square", "default")
                "wrap", "bypass" -> listOf("on", "off")
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    // ---- サブコマンド ----

    private fun registerSubcommands() {
        add(Sub("help", min = 0, max = 10,
            examples = listOf(Example("@[command] - get help on command usage.")),
            status = { sender ->
                val names = subs.keys.filter { it != "commands" }.sorted()
                sender.sendMessage(C_HEAD + "Commands: " + C_CMD + names.joinToString("$C_DESC, $C_CMD"))
                sender.sendMessage("Example, for info on \"set\" command: ${cmd(sender)}${emphasized("help")}${C_CMD}set")
                sender.sendMessage(C_HEAD + "For a full command example list, simply run the root ${cmd(sender)}${C_HEAD}command by itself with nothing specified.")
            },
        ) {
            val self = subs.getValue("help")
            if (params.isEmpty()) {
                self.sendHelp(sender)
                return@Sub
            }
            val target = params.firstNotNullOfOrNull { p -> subs[p.lowercase()]?.takeIf { it.name != "commands" } }
                ?: throw Stop("No command recognized.")
            target.sendHelp(sender)
        })

        add(Sub("set", worldInput = true, consoleNeedsWorld = false, min = 1, max = 4,
            examples = listOf(
                Example("{world} @<radiusX> [radiusZ] <x> <z> - use x/z coords."),
                Example("{world} @<radiusX> [radiusZ] ^spawn - use spawn point."),
                Example("@<radiusX> [radiusZ] - set border, centered on you.", forConsole = false),
                Example("@<radiusX> [radiusZ] ^player <name> - center on player."),
            ),
            help = "Set a border for a world, with several options for defining the center location. [world] is " +
                "optional for players and defaults to the world the player is in. If [radiusZ] is not specified, the " +
                "radiusX value will be used for both. The <x> and <z> coordinates can be decimal values (ex. 1.234).",
        ) { set(this) })

        add(Sub("setcorners", permission = "set", worldInput = true, min = 4, max = 4,
            examples = listOf(Example("{world} @<x1> <z1> <x2> <z2> - corner coords.")),
            help = "This is an alternate way to set a border, by specifying the X and Z coordinates of two opposite " +
                "corners of the border area ((x1, z1) to (x2, z2)). [world] is optional for players and defaults to the " +
                "world the player is in.",
        ) {
            val worldName = world ?: player!!.world.name
            if (world != null && Bukkit.getWorld(worldName) == null) {
                sender.sendMessage("The world you specified (\"$worldName\") could not be found on the server, but data for it will be stored anyway.")
            }
            val c = params.map { it.toDoubleOrNull() ?: throw Stop("The x1, z1, x2, and z2 coordinate values must be numerical.") }
            val old = settings.borders[worldName]
            module.setBorder(worldName, BorderData.fromCorners(c[0], c[1], c[2], c[3], old?.shapeRound, old?.wrapping ?: false))
            if (player != null) sender.sendMessage("Border has been set. " + settings.describe(worldName))
        })

        add(Sub("radius", worldInput = true, min = 1, max = 2,
            examples = listOf(Example("{world} @<radiusX> [radiusZ] - change radius.")),
            help = "Using this command you can adjust the radius of an existing border. If [radiusZ] is not " +
                "specified, the radiusX value will be used for both. You can also optionally specify + or - at the start " +
                "of <radiusX> and [radiusZ] to increase or decrease the existing radius rather than setting a new value.",
        ) {
            val worldName = world ?: player!!.world.name
            val border = settings.borders[worldName] ?: throw Stop("This world (\"$worldName\") must first have a border set normally.")
            val radiusX = relativeRadius(params[0], border.radiusX)
            val radiusZ = if (params.size == 2) relativeRadius(params[1], border.radiusZ) else radiusX
            module.setBorder(worldName, border.copy(radiusX = radiusX, radiusZ = radiusZ))
            if (player != null) sender.sendMessage("Radius has been set. " + settings.describe(worldName))
        })

        add(Sub("list", min = 0, max = 0,
            examples = listOf(Example("@- show border information for all worlds.")),
            help = "This command will list full information for every border you have set including position, " +
                "radius, and shape. The default border shape will also be indicated.",
        ) {
            sender.sendMessage("Default border shape for all worlds is \"${BorderData.shapeName(settings.shapeRound)}\".")
            if (settings.borders.isEmpty()) {
                sender.sendMessage("There are no borders currently set.")
                return@Sub
            }
            settings.borders.keys.forEach { sender.sendMessage(settings.describe(it)) }
        })

        add(Sub("shape", min = 1, max = 1,
            examples = listOf(
                Example("@<round|square> - set the default border shape."),
                Example("@<elliptic|rectangular> - same as above."),
            ),
            help = "Default value: round/elliptic. The default border shape will be used on all worlds which don't " +
                "have an individual shape set using the ${emphasized("wshape")}${C_DESC}command. Elliptic " +
                "and round work the same, as rectangular and square do. The difference is down to whether the X and Z " +
                "radius are the same.",
            status = { it.sendMessage(C_HEAD + "The default border shape for all worlds is currently set to \"${BorderData.shapeName(settings.shapeRound)}\".") },
        ) {
            settings.shapeRound = parseShape(params[0]) ?: throw Stop("You must specify one of the 4 valid shape names below.")
            module.logger.info("Set default border shape to ${BorderData.shapeName(settings.shapeRound)}.")
            module.save()
            module.markers?.showAll()
            if (player != null) showStatus()
        })

        add(Sub("clear", worldInput = true, consoleNeedsWorld = false, min = 0, max = 1,
            examples = listOf(
                Example("{world} @- remove border for this world."),
                Example("@^all - remove border for all worlds."),
            ),
            help = "If run by an in-game player and [world] or \"all\" isn't specified, the world you are currently in is used.",
        ) {
            if (params.size == 1 && params[0].equals("all", ignoreCase = true)) {
                if (world != null) throw Stop("You should not specify a world with \"clear all\".")
                module.removeAllBorders()
                if (player != null) sender.sendMessage("All borders have been cleared for all worlds.")
                return@Sub
            }
            val worldName = world ?: player?.world?.name ?: throw Stop("You must specify a world name from console if not using \"clear all\".")
            if (worldName !in settings.borders) throw Stop("This world (\"$worldName\") does not have a border set.")
            module.removeBorder(worldName)
            if (player != null) sender.sendMessage("Border cleared for world \"$worldName\".")
        })

        for (name in listOf("fill", "trim", "fillautosave")) {
            add(Sub(name, examples = listOf(Example("@- not available in TORO-Core.")),
                help = "World fill/trim is not ported to TORO-Core. Use Paper's chunk pregeneration tools instead.",
            ) { sender.sendMessage(C_ERR + "/wb $name is not available in TORO-Core. Use a chunk pregeneration plugin (e.g. Chunky) instead.") })
        }

        add(Sub("bypass", min = 0, max = 2,
            examples = listOf(Example("@{player} [on|off] - let player go beyond border.")),
            help = "If [player] isn't specified, command sender is used. If [on|off] isn't specified, the value will " +
                "be toggled. Once bypass is enabled, the player will not be stopped by any borders until bypass is " +
                "disabled for them again. Use the ${emphasized("bypasslist")}${C_DESC}command to list all " +
                "players with bypass enabled.",
            status = { sender ->
                if (sender is Player) {
                    sender.sendMessage(C_HEAD + "Border bypass is currently ${enabledColored(sender.uniqueId in settings.bypass)}$C_HEAD for you.")
                }
            },
        ) {
            val self = player
            if (self == null && params.isEmpty()) throw Stop("When running this command from console, you must specify a player.")
            val name = params.firstOrNull() ?: self!!.name
            val uuid = if (params.isEmpty()) self!!.uniqueId else
                Bukkit.getPlayerExact(name)?.uniqueId ?: Bukkit.getOfflinePlayerIfCached(name)?.uniqueId
                    ?: throw Stop("Failed to look up UUID for the player name you specified.")
            val bypassing = if (params.size > 1) strAsBool(params[1]) else uuid !in settings.bypass
            if (bypassing) settings.bypass += uuid else settings.bypass -= uuid
            module.save()
            val target = Bukkit.getPlayer(uuid)
            target?.sendMessage("Border bypass is now ${enabledColored(bypassing)}.")
            module.logger.info("Border bypass for player \"$name\" is ${if (bypassing) "enabled" else "disabled"}" +
                (if (self != null) " at the command of player \"${self.name}\"" else "") + ".")
            if (self != null && self != target) sender.sendMessage("Border bypass for player \"$name\" is ${enabledColored(bypassing)}.")
        })

        add(Sub("bypasslist", min = 0, max = 0,
            examples = listOf(Example("@- list players with border bypass enabled.")),
            help = "The bypass list will persist between server restarts, and applies to all worlds. Use the " +
                "${emphasized("bypass")}${C_DESC}command to add or remove players.",
        ) {
            if (settings.bypass.isEmpty()) {
                sender.sendMessage("Players with border bypass enabled: <none>")
                return@Sub
            }
            val names = settings.bypass.map { Bukkit.getOfflinePlayer(it).name ?: it.toString() }
            sender.sendMessage("Players with border bypass enabled: " + names.joinToString(", "))
        })

        add(Sub("knockback", min = 1, max = 1,
            examples = listOf(Example("@<distance> - how far to move the player back.")),
            help = "Default value: 3.0 (blocks). Players who cross the border will be knocked back to this distance inside.",
            status = { sender ->
                val kb = settings.knockBack
                sender.sendMessage(C_HEAD + if (kb < 1) "Knockback is set to 0, disabling border enforcement." else "Knockback is set to $kb blocks inside the border.")
            },
        ) {
            val blocks = params[0].toDoubleOrNull()?.takeUnless { it < 0.0 || (it > 0.0 && it < 1.0) }
                ?: throw Stop("The knockback must be a decimal value of at least 1.0, or it can be 0.")
            settings.knockBack = blocks
            module.logger.info("Knockback set to $blocks blocks inside the border.")
            module.save()
            if (player != null) showStatus()
        })

        add(Sub("wrap", min = 1, max = 2,
            examples = listOf(Example("@{world} <on|off> - can make border crossings wrap.")),
            help = "When border wrapping is enabled for a world, players will be sent around to the opposite edge " +
                "of the border when they cross it instead of being knocked back. [world] is optional for players and " +
                "defaults to the world the player is in.",
        ) {
            if (player == null && params.size == 1) throw Stop("When running this command from console, you must specify a world.")
            val worldName = if (params.size == 2) params[0] else player!!.world.name
            val wrap = strAsBool(params.last())
            val border = settings.borders[worldName] ?: throw Stop("This world (\"$worldName\") does not have a border set.")
            module.setBorder(worldName, border.copy(wrapping = wrap), log = false)
            sender.sendMessage("Border for world \"$worldName\" is now set to ${if (wrap) "" else "not "}wrap around.")
        })

        add(toggle("whoosh", "<on|off> - turn knockback effect on or off.",
            "Default value: on. This will show a particle effect and play a sound where a player is knocked back from the border.",
            "\"Whoosh\" knockback effect", { settings.whooshEffect }, { settings.whooshEffect = it }))

        add(Sub("getmsg", min = 0, max = 0,
            examples = listOf(Example("@- display border message.")),
            help = "This command simply displays the message shown to players knocked back from the border.",
        ) {
            sender.sendMessage("Border message is currently set to:")
            sender.sendMessage(settings.message)
            sender.sendMessage("Formatted border message:")
            sender.sendMessage(settings.messageFormatted)
        })

        add(Sub("setmsg", min = 1,
            examples = listOf(Example("@<text> - set border message.")),
            help = "Default value: \"${BorderSettings.DEFAULT_MESSAGE}\". This command lets you set the message shown to players who are knocked back from the border.",
            status = { sender ->
                sender.sendMessage(C_HEAD + "Border message is set to:")
                sender.sendMessage(settings.message)
                sender.sendMessage(C_HEAD + "Formatted border message:")
                sender.sendMessage(settings.messageFormatted)
            },
        ) {
            settings.message = params.joinToString(" ")
            module.save()
            showStatus()
        })

        add(Sub("wshape", min = 1, max = 2,
            examples = listOf(
                Example("@{world} <elliptic|rectangular|default> - shape"),
                Example("$C_DESC     override for a single world.", forPlayer = true, forConsole = true, prefix = false),
                Example("@{world} <round|square|default> - same as above."),
            ),
            help = "This will override the default border shape for a single world. The value \"default\" implies " +
                "a world is just using the default border shape. See the ${emphasized("shape")}${C_DESC}" +
                "command for more info and to set the default border shape.",
        ) {
            if (player == null && params.size == 1) throw Stop("When running this command from console, you must specify a world.")
            val worldName = if (params.size == 2) params[0] else player!!.world.name
            val shape = parseShape(params.last())
            val border = settings.borders[worldName] ?: throw Stop("This world (\"$worldName\") does not have a border set.")
            module.setBorder(worldName, border.copy(shapeRound = shape), log = false)
            sender.sendMessage("Border shape for world \"$worldName\" is now set to \"${BorderData.shapeName(shape)}\".")
        })

        add(toggle("preventblockplace", "<on|off> - stop block placement past border.",
            "Default value: off. When enabled, this setting will prevent players from placing blocks outside the world's border.",
            "Prevention of block placement outside the border", { settings.preventBlockPlace }, { settings.preventBlockPlace = it }))

        add(toggle("preventmobspawn", "<on|off> - stop mob spawning past border.",
            "Default value: off. When enabled, this setting will prevent mobs from naturally spawning outside the world's border.",
            "Prevention of mob spawning outside the border", { settings.preventMobSpawn }, { settings.preventMobSpawn = it }))

        add(Sub("delay", min = 1, max = 1,
            examples = listOf(Example("@<amount> - time between border checks.")),
            help = "Default value: 5. The <amount> is in server ticks, of which there are roughly 20 every second, each " +
                "tick taking ~50ms. The default value therefore has border checks run about 4 times per second.",
            status = { it.sendMessage(C_HEAD + "Timer delay is set to ${settings.timerTicks} tick(s). That is roughly ${settings.timerTicks * 50}ms.") },
        ) {
            val delay = params[0].toIntOrNull()?.takeIf { it >= 1 } ?: throw Stop("The timer delay must be an integer of 1 or higher.")
            settings.timerTicks = delay
            module.logger.info("Timer delay set to $delay tick(s). That is roughly ${delay * 50}ms / ${delay * 50 / 1000.0} seconds.")
            module.startTimer()
            module.save()
            if (player != null) showStatus()
        })

        add(toggle("dynmap", "<on|off> - turn DynMap border display on or off.",
            "Default value: on. If you are running the DynMap plugin and this setting is enabled, all borders will be visually shown in DynMap.",
            "DynMap border display", { settings.dynmapEnabled }, { settings.dynmapEnabled = it; module.markers?.showAll() }))

        add(Sub("dynmapmsg", min = 1,
            examples = listOf(Example("@<text> - DynMap border labels will show this.")),
            help = "Default value: \"The border of the world.\". If you are running the DynMap plugin and the " +
                "${emphasized("dynmap")}${C_DESC}command setting is enabled, the borders shown in DynMap will be labelled with this text.",
            status = { it.sendMessage(C_HEAD + "DynMap border label is set to: " + C_ERR + settings.dynmapMessage) },
        ) {
            settings.dynmapMessage = params.joinToString(" ")
            module.logger.info("DynMap border label is now set to: ${settings.dynmapMessage}")
            module.save()
            module.markers?.showAll()
            if (player != null) showStatus()
        })

        add(Sub("remount", min = 1, max = 1,
            examples = listOf(Example("@<amount> - player remount delay after knockback.")),
            help = "Default value: 0 (disabled). If set higher than 0, WorldBorder will attempt to re-mount players who " +
                "are knocked back from the border while riding something after this many server ticks. This setting can " +
                "cause really nasty glitches if enabled and set too low due to CraftBukkit teleportation problems.",
            status = { sender ->
                val delay = settings.remountTicks
                if (delay == 0) {
                    sender.sendMessage(C_HEAD + "Remount delay set to 0. Players will be left dismounted when knocked back from the border while on a vehicle.")
                } else {
                    sender.sendMessage(C_HEAD + "Remount delay set to $delay tick(s). That is roughly ${delay * 50}ms / ${delay * 50 / 1000.0} seconds. Setting to 0 would disable remounting.")
                    if (delay < 10) sender.sendMessage(C_ERR + "WARNING:" + C_DESC + " setting this to less than 10 (and greater than 0) is not recommended. This can lead to nasty client glitches.")
                }
            },
        ) {
            val delay = params[0].toIntOrNull()?.takeIf { it >= 0 }
                ?: throw Stop("The remount delay must be an integer of 0 or higher. Setting to 0 will disable remounting.")
            settings.remountTicks = delay
            module.logger.info("Remount delay set to $delay tick(s).")
            module.save()
            if (player != null) showStatus()
        })

        add(toggle("portal", "<on|off> - turn portal redirection on or off.",
            "Default value: on. This feature monitors new portal creation and changes the target new portal " +
                "location if it is outside of the border. Try disabling this if you have problems with other plugins related to portals.",
            "Portal redirection", { settings.portalRedirection }, { settings.portalRedirection = it }))

        add(toggle("denypearl", "<on|off> - stop ender pearls past the border.",
            "Default value: on. When enabled, this setting will directly cancel attempts to use an ender pearl to " +
                "get past the border rather than just knocking the player back. This should prevent usage of ender " +
                "pearls to glitch into areas otherwise inaccessible at the border edge.",
            "Direct cancellation of ender pearls thrown past the border", { settings.denyEnderpearl }, { settings.denyEnderpearl = it }))

        add(Sub("reload", min = 0, max = 0,
            examples = listOf(Example("@- re-load data from config.yml.")),
            help = "If you make manual changes to config.yml while the server is running, you can use this command " +
                "to make WorldBorder load the changes without needing to restart the server.",
        ) {
            if (player != null) module.logger.info("Reloading config file at the command of player \"${player!!.name}\".")
            module.reload()
            if (player != null) sender.sendMessage("WorldBorder configuration reloaded.")
        })

        add(toggle("debug", "<on|off> - turn console debug output on or off.",
            "Default value: off. Debug mode will show some extra debugging data in the server console/log when " +
                "players are knocked back from the border or are teleported.",
            "Debug mode", { settings.debug }, { settings.debug = it }))

        // 引数なしの /wb で出す、コマンド例の一覧 (最後に置く)
        add(Sub("commands", permission = "help") { commands(this) })
    }

    /** on/off を切り替えるだけのサブコマンド */
    private fun toggle(
        name: String,
        example: String,
        help: String,
        label: String,
        get: () -> Boolean,
        set: (Boolean) -> Unit,
    ): Sub {
        val status: (CommandSender) -> Unit = { it.sendMessage("$C_HEAD$label is ${enabledColored(get())}$C_HEAD.") }
        return Sub(name, min = 1, max = 1, examples = listOf(Example("@$example")), help = help, status = status) {
            set(strAsBool(params[0]))
            module.logger.info("$label ${if (get()) "enabled" else "disabled"}" + (player?.let { " at the command of player \"${it.name}\"" } ?: "") + ".")
            module.save()
            if (player != null) showStatus()
        }
    }

    private fun set(ctx: Ctx) = with(ctx) {
        // 半径だけはプレイヤーのみ (自分の位置を中心にする)
        if (params.size == 1 && player == null) throw Stop("You have not provided a sufficient number of parameters.")
        val last = params.last()
        var worldName = world
        if (worldName != null) {
            if (params.size == 2 && !last.equals("spawn", ignoreCase = true)) {
                throw Stop("You have not provided a sufficient number of arguments.")
            }
            if (Bukkit.getWorld(worldName) == null) {
                if (last.equals("spawn", ignoreCase = true)) {
                    throw Stop("The world you specified (\"$worldName\") could not be found on the server, so the spawn point cannot be determined.")
                }
                sender.sendMessage("The world you specified (\"$worldName\") could not be found on the server, but data for it will be stored anyway.")
            }
        } else {
            if (player == null) {
                // コンソールからワールドを省けるのは、プレイヤーを中心にするときだけ
                if (params.size < 2 || !params[params.size - 2].equals("player", ignoreCase = true)) {
                    throw Stop("You must specify a world name from console if not specifying a player name.")
                }
                player = Bukkit.getPlayer(last) ?: throw Stop("The player you specified (\"$last\") does not appear to be online.")
            }
            worldName = player!!.world.name
        }

        var radiusCount = params.size
        val x: Double
        val z: Double
        try {
            when {
                last.equals("spawn", ignoreCase = true) -> {
                    val spawn = Bukkit.getWorld(worldName)!!.spawnLocation
                    x = spawn.x
                    z = spawn.z
                    radiusCount -= 1
                }
                params.size > 2 && params[params.size - 2].equals("player", ignoreCase = true) -> {
                    val target = Bukkit.getPlayer(last) ?: throw Stop("The player you specified (\"$last\") does not appear to be online.")
                    worldName = target.world.name
                    x = target.location.x
                    z = target.location.z
                    radiusCount -= 2
                }
                player == null || radiusCount > 2 -> {
                    x = params[params.size - 2].toDouble()
                    z = last.toDouble()
                    radiusCount -= 2
                }
                else -> {
                    x = player!!.location.x
                    z = player!!.location.z
                }
            }
            val radiusX = params[0].toInt()
            val radiusZ = if (radiusCount < 2) radiusX else params[1].toInt()
            if (radiusX < settings.knockBack || radiusZ < settings.knockBack) throw Stop("Radius value(s) must be more than the knockback distance.")

            val old = settings.borders[worldName]
            module.setBorder(worldName, BorderData(x, z, radiusX, radiusZ, old?.shapeRound, old?.wrapping ?: false))
        } catch (e: NumberFormatException) {
            throw Stop("Radius value(s) must be integers and x and z values must be numerical.")
        }
        sender.sendMessage("Border has been set. " + settings.describe(worldName))
    }

    private fun commands(ctx: Ctx) = with(ctx) {
        val examples = if (player == null) allConsoleExamples else allPlayerExamples
        val pageCount = ceil(examples.size / PAGE_SIZE.toDouble()).toInt()
        var page = params.firstOrNull()?.toIntOrNull() ?: if (player == null) 0 else 1
        if (page < 0 || page > pageCount) page = if (player == null) 0 else 1

        sender.sendMessage("${C_HEAD}WorldBorder (TORO-Core ${module.plugin.pluginMeta.version})  -  key: ${emphasized("command")}$C_REQ<required> $C_OPT[optional]")
        if (page == 0) {
            examples.forEach(sender::sendMessage)
            return@with
        }
        val first = (page - 1) * PAGE_SIZE
        examples.subList(first, minOf(first + PAGE_SIZE, examples.size)).forEach(sender::sendMessage)
        val footer = "$C_HEAD (Page $page/$pageCount)              ${cmd(sender)}"
        if (page < pageCount) sender.sendMessage("$footer${page + 1}$C_DESC - view next page of commands.")
        else if (page > 1) sender.sendMessage("$footer$C_DESC- view first page of commands.")
    }

    private fun relativeRadius(text: String, current: Int): Int = try {
        when {
            text.startsWith("+") -> current + text.substring(1).toInt()
            text.startsWith("-") -> current - text.substring(1).toInt()
            else -> text.toInt()
        }
    } catch (e: NumberFormatException) {
        throw Stop("The radius value(s) must be integers.")
    }

    private companion object {
        const val PAGE_SIZE = 8

        // 色 (元の WBCmd と同じ)
        const val C_CMD = "§b"
        const val C_DESC = "§f"
        const val C_ERR = "§c"
        const val C_HEAD = "§e"
        const val C_OPT = "§2"
        const val C_REQ = "§a"

        val ON_OFF_COMMANDS = setOf("whoosh", "preventblockplace", "preventmobspawn", "dynmap", "portal", "denypearl", "debug")

        fun cmd(sender: CommandSender) = if (sender is Player) "$C_CMD/wb " else "${C_CMD}wb "

        fun emphasized(text: String) = "$C_CMD§n$text§r "

        fun enabledColored(enabled: Boolean) = if (enabled) "${C_REQ}enabled" else "${C_ERR}disabled"

        /** yes/no, true/false, on/off, +/-, 1/0 */
        fun strAsBool(s: String): Boolean {
            val str = s.lowercase()
            return str.startsWith("y") || str.startsWith("t") || str.startsWith("on") || str.startsWith("+") || str.startsWith("1")
        }

        /** round/elliptic なら true、square/rectangular なら false、それ以外は null (default) */
        fun parseShape(s: String): Boolean? = when (s.lowercase()) {
            "elliptic", "round" -> true
            "rectangular", "square" -> false
            else -> null
        }

        /** コマンド例に色を付ける。`@` はサブコマンド名、`{}` はプレイヤーなら省略可・コンソールなら必須 */
        fun formatExample(example: Example, name: String, forPlayer: Boolean): String {
            val text = example.text.replace("@", emphasized(name))
                .replace("<", "$C_REQ<").replace("[", "$C_OPT[").replace("^", C_CMD).replace("- ", "$C_DESC- ")
            return if (forPlayer) {
                (if (example.prefix) "$C_CMD/wb " else "") + text.replace("{", "$C_OPT[").replace("}", "]")
            } else {
                (if (example.prefix) "${C_CMD}wb " else "") + text.replace("{", "$C_REQ<").replace("}", ">")
            }
        }
    }
}
