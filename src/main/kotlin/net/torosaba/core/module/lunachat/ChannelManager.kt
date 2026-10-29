package net.torosaba.core.module.lunachat

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * チャンネルとプレイヤーごとのデータ (LunaChat の ChannelManager)。ファイル名と書式は LunaChat と同じ:
 *
 * - `defaults.yml` プレイヤー名 → 発言先のチャンネル名 (小文字)
 * - `templates.yml` 番号 → フォーマットのテンプレート
 * - `japanize.yml` プレイヤー名 → Japanize するか
 * - `dictionary.yml` 語 → 変換後
 * - `hidelist.yml` 非表示にされている人の ID → 非表示にしている人の ID の一覧
 * - `channels/<name>.yml` チャンネル
 */
internal class ChannelManager(private val lc: LunaChatModule) {
    private val folder: File get() = lc.dataFolder
    private val fileDefaults get() = File(folder, "defaults.yml")
    private val fileTemplates get() = File(folder, "templates.yml")
    private val fileJapanize get() = File(folder, "japanize.yml")
    private val fileDictionary get() = File(folder, "dictionary.yml")
    private val fileHidelist get() = File(folder, "hidelist.yml")

    /** 小文字のチャンネル名 → チャンネル */
    private val channelMap = ConcurrentHashMap<String, Channel>()
    private val defaultChannels = ConcurrentHashMap<String, String>()
    private val templates = ConcurrentHashMap<String, String>()
    private val japanize = ConcurrentHashMap<String, Boolean>()

    // 辞書は LunaChat と同じ HashMap にして、置き換えの順番をそろえる
    private val dictionary = HashMap<String, String>()
    private val hidelist = ConcurrentHashMap<String, CopyOnWriteArrayList<ChannelMember>>()

    val channels: Collection<Channel> get() = channelMap.values

    fun reloadAll() {
        folder.mkdirs()
        val warn: (Exception) -> Unit = { lc.logger.warning("データファイルを読めませんでした: $it") }

        defaultChannels.clear()
        for ((k, v) in load(fileDefaults, warn)) if (v != null) defaultChannels[k] = v.toString().lowercase()

        templates.clear()
        for ((k, v) in load(fileTemplates, warn)) if (v != null) templates[k] = v.toString()

        japanize.clear()
        for ((k, v) in load(fileJapanize, warn)) japanize[k] = v == true || v.toString().equals("true", true)

        synchronized(dictionary) {
            dictionary.clear()
            for ((k, v) in load(fileDictionary, warn)) if (v != null) dictionary[k] = v.toString()
        }

        hidelist.clear()
        for ((k, v) in load(fileHidelist, warn)) {
            val list = (v as? List<*>)?.mapNotNull { it?.toString()?.let(lc::memberById) } ?: emptyList()
            hidelist[k] = CopyOnWriteArrayList(list)
        }

        channelMap.clear()
        val files = File(folder, Channel.FOLDER).listFiles { _, n -> n.endsWith(".yml") } ?: emptyArray()
        for (file in files) {
            val channel = Channel.deserialize(lc, FlatYaml.load(file, warn)) ?: continue
            channelMap[channel.name.lowercase()] = channel
        }
    }

    /** ファイルが無ければ空のファイルを作ってから読む (LunaChat と同じ) */
    private fun load(file: File, warn: (Exception) -> Unit): Map<String, Any?> {
        if (!file.exists()) save(file, emptyMap())
        return FlatYaml.load(file, warn)
    }

    private fun save(file: File, data: Map<String, Any?>) {
        try {
            FlatYaml.save(file, data)
        } catch (e: Exception) {
            lc.logger.warning("${file.name} を保存できませんでした: $e")
        }
    }

    private fun saveDefaults() = save(fileDefaults, defaultChannels.toSortedMap())
    private fun saveTemplates() = save(fileTemplates, templates.toSortedMap())
    private fun saveJapanize() = save(fileJapanize, japanize.toSortedMap())
    private fun saveDictionary() = save(fileDictionary, synchronized(dictionary) { LinkedHashMap<String, Any?>(dictionary) })
    private fun saveHidelist() = save(fileHidelist, hidelist.entries.associate { (k, v) -> k to v.map { it.id } })

    fun removeAllDefaultChannels() {
        defaultChannels.clear()
        saveDefaults()
    }

    // ---- Japanize ----

    fun isPlayerJapanize(playerName: String): Boolean = japanize[playerName] ?: lc.settings.japanizePlayerDefault

    fun setPlayersJapanize(playerName: String, doJapanize: Boolean) {
        japanize[playerName] = doJapanize
        saveJapanize()
    }

    // ---- チャンネル ----

    fun isExistChannel(name: String?): Boolean = name != null && channelMap.containsKey(name.lowercase())

    /** チャンネル名か別名 (大文字小文字は問わない) から引く */
    fun getChannel(name: String?): Channel? {
        if (name == null) return null
        channelMap[name.lowercase()]?.let { return it }
        return channelMap.values.firstOrNull { it.alias.isNotEmpty() && it.alias.equals(name, ignoreCase = true) }
    }

    fun getChannelsByPlayer(member: ChannelMember): List<Channel> =
        channelMap.values.filter { member in it.members || it.isGlobalChannel }

    fun createChannel(name: String): Channel {
        val channel = Channel(lc, name)
        channelMap[name.lowercase()] = channel
        channel.save()
        return channel
    }

    /** チャンネルを消す。残っているメンバーには解散のメッセージを送る */
    fun removeChannel(name: String): Boolean {
        val channel = getChannel(name) ?: return true
        val message = lc.messages.plain("breakupMessage", "color" to channel.colorCode, "channel" to channel.name)
        if (!channel.isPersonalChat && message.isNotEmpty()) channel.members.forEach { it.send(message) }
        channel.deleteFile()
        channelMap.remove(channel.name.lowercase())
        return true
    }

    // ---- 発言先 ----

    fun getDefaultChannel(playerName: String): Channel? {
        val name = defaultChannels[playerName] ?: return null
        return channelMap[name]
    }

    fun setDefaultChannel(playerName: String, channelName: String?) {
        if (channelName == null) {
            removeDefaultChannel(playerName)
            return
        }
        defaultChannels[playerName] = channelName.lowercase()
        saveDefaults()
    }

    fun removeDefaultChannel(playerName: String) {
        defaultChannels.remove(playerName)
        saveDefaults()
    }

    // ---- テンプレート ----

    fun getTemplate(id: String): String? = templates[id]

    fun setTemplate(id: String, template: String) {
        templates[id] = template
        saveTemplates()
    }

    fun removeTemplate(id: String) {
        templates.remove(id)
        saveTemplates()
    }

    // ---- 辞書 ----

    /** 辞書の写し (LunaChat と同じ順番) */
    fun dictionarySnapshot(): Map<String, String> = synchronized(dictionary) { LinkedHashMap(dictionary) }

    fun setDictionary(key: String, value: String) {
        synchronized(dictionary) { dictionary[key] = value }
        saveDictionary()
    }

    fun removeDictionary(key: String) {
        synchronized(dictionary) { dictionary.remove(key) }
        saveDictionary()
    }

    // ---- 非表示 ----

    /** [member] を非表示にしている人 */
    fun getHidelist(member: ChannelMember?): List<ChannelMember> =
        member?.let { hidelist[it.id] } ?: emptyList()

    /** [player] が非表示にしている人 */
    fun getHideinfo(player: ChannelMember): List<ChannelMember> =
        hidelist.entries.filter { player in it.value }.mapNotNull { lc.memberById(it.key) }

    fun addHidelist(player: ChannelMember, hided: ChannelMember) {
        val list = hidelist.computeIfAbsent(hided.id) { CopyOnWriteArrayList() }
        if (list.addIfAbsent(player)) saveHidelist()
    }

    fun removeHidelist(player: ChannelMember, hided: ChannelMember) {
        val list = hidelist[hided.id] ?: return
        if (list.remove(player)) {
            if (list.isEmpty()) hidelist.remove(hided.id)
            saveHidelist()
        }
    }
}
