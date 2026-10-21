rootProject.name = "KickCloudstream"

// Include every top-level folder that has a build.gradle.kts as a plugin module
File(rootDir, ".").listFiles()
    ?.filter { it.isDirectory && File(it, "build.gradle.kts").exists() }
    ?.forEach { include(it.name) }
