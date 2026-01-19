plugins {
    java
}

group = "com.hyfixes"
version = "1.7.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    flatDir {
        dirs("libs")
    }
}

dependencies {
    // Hytale Server API - place HytaleServer.jar in libs/ folder
    compileOnly(files("libs/HytaleServer.jar"))

    // Annotations
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
}

// Task to update manifest.json with current version
tasks.register("updateManifestVersion") {
    doLast {
        val manifestFile = file("src/main/resources/manifest.json")
        if (manifestFile.exists()) {
            val content = manifestFile.readText()
            val updated = content.replace(
                Regex(""""Version":\s*"[^"]*""""),
                """"Version": "${project.version}""""
            )
            manifestFile.writeText(updated)
            println("Updated manifest.json to version ${project.version}")
        }
    }
}

tasks.jar {
    dependsOn("updateManifestVersion")
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
            "Plugin-Class" to "com.hyfixes.HyFixes"
        )
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
