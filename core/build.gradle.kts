plugins { `java-library` }

dependencies {
    // JSON の読み書き（fabric.mod.json・素材 JSON・IR）。core の唯一の実行時依存。
    api(libs.gson)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

// バージョンカタログを唯一の正本とし、実行時にも同じ値を読めるよう生成する。
val targetVersions = mapOf(
    "minecraft" to libs.versions.minecraft.get(),
    "fabric-loader" to libs.versions.fabric.loader.get(),
    "fabric-api" to libs.versions.fabric.api.get(),
    "geyser" to libs.versions.geyser.get(),
    "hydraulic" to libs.versions.hydraulic.get(),
    "java" to libs.versions.java.get(),
    "modbridge" to project.version.toString(),
)
val generateVersions by tasks.registering {
    val out = layout.buildDirectory.dir("generated/versions")
    val entries = targetVersions.toSortedMap()
    inputs.property("versions", entries)
    outputs.dir(out)
    doLast {
        val f = out.get().file("dev/puicore/modbridge/core/target-versions.properties").asFile
        f.parentFile.mkdirs()
        f.writeText(entries.entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" })
    }
}
sourceSets.main { resources.srcDir(generateVersions) }
