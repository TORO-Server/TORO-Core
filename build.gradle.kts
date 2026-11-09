plugins {
    kotlin("jvm") version "2.4.21"
    id("com.gradleup.shadow") version "9.6.1"
}

group = "net.torosaba"
version = "1.0.0"

// 各リポジトリから取るグループを限定する。Maven Central を最後にして、下のグループは先に専用のリポジトリを見る
repositories {
    maven("https://repo.papermc.io/repository/maven-public/") {
        content {
            includeGroup("io.papermc.paper")
            includeGroup("com.mojang")
            includeGroup("net.md-5")
        }
    }
    maven("https://repo.bluecolored.de/releases") {
        content {
            includeGroup("de.bluecolored")
        }
    }
    maven("https://repo.mikeprimm.com/") {
        content {
            includeGroup("us.dynmap")
        }
    }
    maven("https://repo.essentialsx.net/releases/") {
        content {
            includeGroup("net.essentialsx")
        }
    }
    maven("https://jitpack.io") {
        content {
            includeGroup("com.github.MilkBowl")
        }
    }
    maven("https://maven.enginehub.org/repo/") {
        content {
            includeGroup("com.sk89q.worldedit")
            includeGroup("com.sk89q.worldguard")
            includeGroup("com.sk89q.worldguard.worldguard-libs")
            // worldedit-core の POM が読み込む BOM
            includeGroup("org.enginehub.lin-bus")
            includeGroup("org.enginehub.lin-bus.format")
            // worldedit-core の推移依存 (テストで実物を読み込むために要る)
            includeGroup("com.sk89q.worldedit.worldedit-libs")
            includeGroup("com.sk89q.lib")
            includeGroup("com.sk89q")
        }
    }
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(kotlin("stdlib"))

    compileOnly("io.papermc.paper:paper-api:26.3.build.227-stable")
    // 連携先のプラグイン。入っていないときは各モジュールがその機能だけを止める
    compileOnly("us.dynmap:DynmapCoreAPI:3.8") { isTransitive = false }
    compileOnly("us.dynmap:dynmap-api:3.8") { isTransitive = false }
    compileOnly("net.luckperms:api:5.4")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1") { isTransitive = false }
    compileOnly("net.essentialsx:EssentialsX:2.21.2") { isTransitive = false }
    compileOnly("com.sk89q.worldguard:worldguard-core:7.0.20") { isTransitive = false }
    compileOnly("com.sk89q.worldguard.worldguard-libs:core:7.0.20") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-core:7.4.6") { isTransitive = false }
    // regions の Vector2i (flow-math) が API の署名に要るので、これは推移依存を有効にする
    compileOnly("de.bluecolored:bluemap-api:2.7.4")

    testImplementation("io.papermc.paper:paper-api:26.3.build.227-stable")
    // WorldEdit の記録経路を実物に近い形でテストするためのもの。本番のスコープ (compileOnly) は変えない
    testImplementation("com.sk89q.worldedit:worldedit-core:7.4.6")
    testImplementation("com.google.guava:guava:32.1.2-jre")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") { expand(props) }
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveClassifier.set("thin")
}

// Kotlin の標準ライブラリは JAR に入れる。ほかの Kotlin 製プラグインと衝突しないよう、パッケージを移す
tasks.shadowJar {
    archiveBaseName.set("TORO-Core")
    archiveClassifier.set("")
    relocate("kotlin", "net.torosaba.core.libs.kotlin")
    relocate("org.jetbrains.annotations", "net.torosaba.core.libs.annotations")
    relocate("org.intellij.lang.annotations", "net.torosaba.core.libs.intellij")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
