# TORO-Core

TORO サーバーで使う、小さな自作プラグインと、開発の止まったプラグインの機能を、1 つにまとめた Minecraft (Paper) 用のプラグインです。Kotlin で書かれています。

機能ごとに「モジュール」に分かれていて、1 つずつ止められます。モジュールの一覧と、既定で有効かどうかは [src/main/resources/config.yml](src/main/resources/config.yml) にあります。コマンド名・権限・設定のキーは、元のプラグインと同じです。

## 使い方

1. 元のプラグインの JAR (プラグイン本体のファイル) を `plugins/` から外す (残すと同じ機能が二重に動きます)
2. `TORO-Core-<バージョン>.jar` を `plugins/` に入れて起動する
3. モジュールを止めるときは `plugins/TORO-Core/config.yml` の `modules.<モジュール>` を `false` にして `/torocore reload`

管理コマンドは `/torocore` (権限 `torocore.admin`) です。`/torocore reload [モジュール]` で、全体または 1 つのモジュールを読み込み直します。

## ビルド

```
./gradlew build
```

必要なものは JDK (Java の開発用の道具一式) 25 です (`build.gradle.kts` の `jvmToolchain`)。配る JAR は `build/libs/` にできます。

## 関連文書

- 元のプラグインとの対応、移行の注意: [docs/migration.md](docs/migration.md)
- 他者製のコードの帰属: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
