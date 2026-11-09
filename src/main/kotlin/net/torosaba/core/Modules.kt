package net.torosaba.core

import net.torosaba.core.module.addperm.AddPermModule
import net.torosaba.core.module.armorstand.ArmorStandModule
import net.torosaba.core.module.automessage.AutoMessageModule
import net.torosaba.core.module.banplayersheet.BanPlayerSheetModule
import net.torosaba.core.module.clickminecart.ClickMinecartModule
import net.torosaba.core.module.clock.ClockModule
import net.torosaba.core.module.clockitemtimer.ClockItemTimerModule
import net.torosaba.core.module.dispenseractions.DispenserActionsModule
import net.torosaba.core.module.dispenserfallingblock.DispenserFallingBlockModule
import net.torosaba.core.module.dynmaplive.DynmapLiveModule
import net.torosaba.core.module.dynmapworldguard.DynmapWorldGuardModule
import net.torosaba.core.module.effectgui.EffectGuiModule
import net.torosaba.core.module.hatcolorteam.HatColorTeamModule
import net.torosaba.core.module.hidenametag.HideNametagModule
import net.torosaba.core.module.infinitydispense.InfinityDispenseModule
import net.torosaba.core.module.inventorybackup.InventoryBackupModule
import net.torosaba.core.module.itemdropcooldown.ItemDropCooldownModule
import net.torosaba.core.module.killpotiondeathlog.KillPotionDeathLogModule
import net.torosaba.core.module.lift.LiftModule
import net.torosaba.core.module.lunachat.LunaChatModule
import net.torosaba.core.module.mapdirty.MapDirtyModule
import net.torosaba.core.module.raidwatcher.RaidWatcherModule
import net.torosaba.core.module.report.ReportModule
import net.torosaba.core.module.seemore.SeeMoreModule
import net.torosaba.core.module.skills.SkillsModule
import net.torosaba.core.module.teleportspectator.TeleportSpectatorModule
import net.torosaba.core.module.textbeacon.TextBeaconModule
import net.torosaba.core.module.tpslog.TpsLogModule
import net.torosaba.core.module.warnkillpotion.WarnKillPotionModule
import net.torosaba.core.module.worldborder.WorldBorderModule

/** 読み込むモジュールの一覧。この順に有効にし、逆順に止める */
object Modules {
    fun create(): List<Module> = listOf(
        AddPermModule(),
        ArmorStandModule(),
        AutoMessageModule(),
        BanPlayerSheetModule(),
        ClickMinecartModule(),
        ClockModule(),
        ClockItemTimerModule(),
        DispenserActionsModule(),
        DispenserFallingBlockModule(),
        EffectGuiModule(),
        HatColorTeamModule(),
        HideNametagModule(),
        InfinityDispenseModule(),
        InventoryBackupModule(),
        ItemDropCooldownModule(),
        KillPotionDeathLogModule(),
        RaidWatcherModule(),
        ReportModule(),
        SkillsModule(),
        TeleportSpectatorModule(),
        TextBeaconModule(),
        TpsLogModule(),
        WarnKillPotionModule(),
        LunaChatModule(),
        WorldBorderModule(),
        LiftModule(),
        SeeMoreModule(),
        DynmapWorldGuardModule(),
        DynmapLiveModule(),
        MapDirtyModule(),
    )
}
