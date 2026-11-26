plugins { base }

subprojects {
    apply(plugin = "java")
    group = rootProject.group
    version = rootProject.version

    // JDK 25 以上で実行し、成果物は Java 25 バイトコードに固定する（ADR-0002）。
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(25)
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("file.encoding", "UTF-8")
    }
    tasks.withType<AbstractArchiveTask>().configureEach {
        // 同じ入力から同じ成果物を作る（再現可能ビルド）。
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}
