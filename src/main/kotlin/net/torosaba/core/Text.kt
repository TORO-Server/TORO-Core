package net.torosaba.core

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer

private val ampersand = LegacyComponentSerializer.legacyAmpersand()
private val section = LegacyComponentSerializer.legacySection()

/** `&a` などの色コードを含む文字列を Component にする */
fun String.legacy(): Component = ampersand.deserialize(this)

/** `&a` などの色コードを `§a` に置き換える */
fun String.colorCodes(): String = section.serialize(ampersand.deserialize(this))

/** Component を `§` の色コード付き文字列にする */
fun Component.legacyText(): String = section.serialize(this)
