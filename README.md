# TORO-Core

TORO サーバー (torosaba.net, Paper 26.3) の小さな自作プラグインと、開発の止まったプラグインの機能を
1 つにまとめた Kotlin 製のプラグイン。機能ごとに「モジュール」に分かれていて、1 つずつ止められる。

## モジュール

| モジュール | 元のプラグイン | コマンド |
|---|---|---|
| `add-perm` | AddPerm (TORO-Server/addperm) | `/addbuilder` `/delbuilder` `/adddriver` `/deldriver` |
| `armor-stand` | Armor_stand | - |
| `auto-message` | Auto_message | - |
| `ban-player-sheet` | BanPlayerSheet | `/sban` `/unsban` |
| `clock` | ClockPlugin | - |
| `clock-item-timer` | ClockItemTimer | - |
| `dispenser-actions` | DispenserActions | - |
| `dispenser-falling-block` | Dispenser_FallingBlock | - |
| `effect-gui` | Effect_GUI | `/effectgui` |
| `hat-color-team` | HatColorTeam | - |
| `hide-nametag` | HideNametagCMD | `/nttoggle` |
| `infinity-dispense` | InfinityDispense | - |
| `inventory-backup` | InventoryBackup | `/restore` |
| `item-drop-cooldown` | ItemDropCooldown | - |
| `kill-potion-death-log` | EssKillPotionBack (KillPotionDeathLog) | - |
| `raid-watcher` | raidwatcher | - |
| `report` | Report | `/report` `/reportblock` |
| `skills` | Skills | `/skill` |
| `teleport-spectator` | TeleportSpectator | `/stp` |
| `text-beacon` | TextBeacon | `/tb` |
| `tps-log` | Tps_log | - |
| `warn-kill-potion` | Warn_KillPotion | - |
| `lunachat` | LunaChat 3 (ucchy) | `/ch` (`/lunachat` `/lc`) `/tell` (`/msg` ほか) `/r` `/jp` |
| `world-border` | WorldBorder (Brettflan) | `/wb` (`/wborder`) |
| `lift` | Lift (croxis) | `/lift` |
| `see-more` | SeeMore (froobynooby) | `/seemore` |
| `dynmap-worldguard` | Dynmap-WorldGuard (webbukkit) | - |

コマンド名・権限ノード・設定のキーは元のプラグインと同じ。

他者製のプラグイン (LunaChat / WorldBorder / Lift / SeeMore / Dynmap-WorldGuard) は、TORO で使っている機能だけを作り直している。

- LunaChat: BungeeCord 連携・mcMMO 連携・他プラグイン向けの API は無い。そのため DiscordSRV の LunaChat 連携 (チャンネルチャットの Discord 転送) は使えない。通常チャットは転送される
- WorldBorder: `fill` / `trim` (ワールドの事前生成・削除) は無い。Paper や別のプラグインの事前生成を使う

## 導入 (元のプラグインからの移行)

1. 下の表の元のプラグインの JAR を `plugins/` から外す (残すと同じ機能が二重に動く)
2. `TORO-Core-<version>.jar` を `plugins/` に入れて起動する
3. 各モジュールは初回の有効化で `plugins/<元のプラグイン名>/` の中身を `plugins/TORO-Core/<モジュール>/` に写して、そのまま使う。
   元のフォルダは消さないので、確認が済んだら手で片付ける
4. モジュールを止めるときは `plugins/TORO-Core/config.yml` の `modules.<モジュール>` を `false` にして `/torocore reload`

外す JAR: `add-parm` `armor_stand` `auto_message` `BanPlayerSheet` `clock` `clock_item_timer` `DispenserActions`
`dispenser_falling_block` `effect_gui` `HatColorTeam` `hide_nametag_cmd` `InfinityDispense` `InventoryBackup`
`item_drop_cooldown` `KillPotionDeathLog` `raidwatcher` `report` `skills` `teleport_spectator` `TextBeacon`
`tps_log` `Warn_KillPotion` `LunaChat` `WorldBorder` `Lift` `SeeMore` `Dynmap-WorldGuard`

移行の注意:

- LunaChat の表示言語は `lunachat/config.yml` の `lang` で決まる (TORO-Configs の本番設定は `ja`)。移す前のサーバーの設定が `en` のままなら英語になる
- `dynmap-worldguard` は WorldGuard の領域のフラグ `dynmap-boost` も登録する。WorldGuard より後に読み込むよう `softdepend` に入れてある
- InventoryBackup の `restore-command` にある `/inventorybackup:restore` は、自動で `/toro-core:restore` に読み替える
- WorldBorder の本番の境界 (`worlds:`) は TORO-Configs の公開用設定に入っていないので、サーバーの `plugins/WorldBorder/config.yml` から移ることを確認する

管理コマンド (`torocore.admin`, 既定 OP):

- `/torocore` — モジュールの一覧
- `/torocore reload [モジュール]` — 全体、または 1 つのモジュールを読み込み直す

## ビルド

JDK 25 と Gradle 9 (ラッパーは無いので `gradle` を使う)。

```
gradle build
```

`build/libs/TORO-Core-<version>.jar` が配布する JAR (Kotlin の標準ライブラリを `net.torosaba.core.libs.kotlin` に移して同梱)。

## 構成

- `src/main/kotlin/net/torosaba/core/` — 本体。`Module` がモジュールの基底クラスで、設定の読み込み・旧フォルダからの移行・
  リスナー/タスク/コマンド/権限の登録と、停止時の解除を受け持つ。コマンドは plugin.yml に書かず、モジュールが有効になったときに登録する。
  ほかのプラグインが有効になる前に済ませること (WorldGuard のフラグの登録など) は `Module.onServerLoad` に書く
- `src/main/kotlin/net/torosaba/core/module/<モジュール>/` — 各モジュール
- `src/main/resources/modules/<モジュール id>/` — 各モジュールの既定の設定
- モジュールを足すときは `Module` を継承したクラスを作り、`Modules.kt` の一覧と `config.yml` の `modules` に加える

## ライセンス

他者製のプラグインを元にしたモジュールのライセンスは [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) を参照。
