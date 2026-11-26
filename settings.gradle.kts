rootProject.name = "pui-modbridge"

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

include("core", "cli")
// fixtures/fabric-fixture は Minecraft 本体を取得する独立ビルド（Fabric Loom）。
// 通常のビルドとテストを外部配布物へ依存させないため、ここには含めない。
