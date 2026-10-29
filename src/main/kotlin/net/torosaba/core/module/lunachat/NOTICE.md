# lunachat モジュールについて

このディレクトリ (`net.torosaba.core.module.lunachat` とその下のパッケージ) と、
`src/main/resources/modules/lunachat/` のファイルは、次のソフトウェアを元にしています。

- **LunaChat** — https://github.com/ucchyocean/LunaChat
  - Copyright ucchy (and contributors: YukiLeafX, LazyGon, tomo1560)
  - ライセンス: GNU Lesser General Public License v3.0 (LGPL-3.0)
- `japanize/KanaConverter.kt` のローマ字かな変換表は、LunaChat に含まれる
  **YukiKanaConverter** (作者 YukiLeafX) を移したものです。
- `messages/messages_ja.yml` のメッセージは、LunaChat の `messages_ja.yml` を元にしています。

元のコードは Java で書かれており、ここでは Kotlin に書き直し、TORO サーバーで使う機能だけを残しています。
このモジュールは LGPL-3.0 のもとで配布します。ライセンスの全文は
https://www.gnu.org/licenses/lgpl-3.0.html (と、それが参照する GPL-3.0 https://www.gnu.org/licenses/gpl-3.0.html) を参照してください。

LGPL-3.0 に従い、このモジュールを含む JAR を配布するときは、このモジュールのソースコード (変更を含む) を
受け取れるようにし、このライセンス表示を残してください。
