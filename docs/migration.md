# 移行と各モジュールの補足

概要は [README.md](../README.md) にあります。この文書は、元のプラグインとの対応、移行の手順と注意、他者製のプラグインの再実装で省いたものをまとめた、ただ 1 つの詳しい文書です。

モジュールの一覧と既定の有効・無効の正本は [`src/main/resources/config.yml`](../src/main/resources/config.yml) です。次の表は、元のプラグインがあるものの対応です。`click-minecart` (単体のプラグインからの移行が終わるまでは既定で無効) と `map-dirty` (ブロック変更の記録だけをする。既定で無効) は、表に含めていません。

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
| `dynmap-live` | (新しく作った機能) | - |

`dynmap-live` は、地図にプレイヤーの動きをすぐ出すため、全員の位置と体力を短い間隔 (既定は 5 tick。tick はゲームの時間の単位で、1 秒は 20 tick なので 0.25 秒。`interval-ticks`) で、地図の画面のサービス ([TORO-WebMap](https://github.com/TORO-Server/TORO-WebMap)) へ WebSocket で送る。地図に誰を出すかは dynmap が決める。`dynmap-live/config.yml` の `token` は、受け取る側と同じ値にする (値は git に入れず、環境ごとに決める)。`endpoint` か `token` が空のときは送らない。

コマンド名・権限ノード・設定のキーは元のプラグインと同じ。

他者製のプラグイン (LunaChat / WorldBorder / Lift / SeeMore / Dynmap-WorldGuard) は、TORO で使っている機能だけを作り直している。

- LunaChat: BungeeCord 連携・mcMMO 連携・他プラグイン向けの API は無い。そのため DiscordSRV の LunaChat 連携 (チャンネルチャットの Discord 転送) は使えない。通常チャットは転送される
- WorldBorder: `fill` / `trim` (ワールドの事前生成・削除) は無い。Paper や別のプラグインの事前生成を使う

## 元のプラグインからの移行

1. 下の表の元のプラグインの JAR を `plugins/` から外す (残すと同じ機能が二重に動く)
2. `TORO-Core-<バージョン>.jar` を `plugins/` に入れて起動する
3. 各モジュールは初回の有効化で `plugins/<元のプラグイン名>/` の中身を `plugins/TORO-Core/<モジュール>/` に写して、そのまま使う。
   元のフォルダは消さないので、確認が済んだら手で片付ける
4. モジュールを止めるときは `plugins/TORO-Core/config.yml` の `modules.<モジュール>` を `false` にして `/torocore reload`

外す JAR: `add-parm` `armor_stand` `auto_message` `BanPlayerSheet` `clock` `clock_item_timer` `DispenserActions`
`dispenser_falling_block` `effect_gui` `HatColorTeam` `hide_nametag_cmd` `InfinityDispense` `InventoryBackup`
`item_drop_cooldown` `KillPotionDeathLog` `raidwatcher` `report` `skills` `teleport_spectator` `TextBeacon`
`tps_log` `Warn_KillPotion` `LunaChat` `WorldBorder` `Lift` `SeeMore` `Dynmap-WorldGuard`

移行の注意:

- LunaChat の表示言語は `lunachat/config.yml` の `lang` で決まる。移す前のサーバーの設定が `en` のままなら英語になる
- `dynmap-worldguard` は WorldGuard の領域のフラグ `dynmap-boost` も登録する。WorldGuard より後に読み込むよう `softdepend` に入れてある
- InventoryBackup の `restore-command` にある `/inventorybackup:restore` は、自動で `/toro-core:restore` に読み替える
- InventoryBackup の復元の案内は、アイテムが消えたと言い切れるとき (クリアのコマンド、何も落とさずに死んだとき、クリエイティブのインベントリで空にしたとき) だけ出す
- raid-watcher の `HighRateLoginBlocker.allowed` は、全体ではなく IP ごと・アカウントごとの回数になった。全体の上限は `allowed-global`
- `/skill` の道具は 1 つだけ渡す。クラスを変えると回収し、捨てることや容器へ移すことはできない
- WorldBorder の境界 (`worlds:`) は、元のプラグインの `plugins/WorldBorder/config.yml` から移ることを確認する

管理コマンド (`torocore.admin`, 既定 OP):

- `/torocore` — モジュールの一覧
- `/torocore reload [モジュール]` — 全体、または 1 つのモジュールを読み込み直す
