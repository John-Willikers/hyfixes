plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.hyfixes"
// Version is set in manifest.json - don't let gradle override it
val projectVersion = "1.11.0"
version = projectVersion

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

    // SQLite for bed chunk storage (Issue #44)
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
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

// Shadow JAR configuration - includes SQLite
tasks.shadowJar {
    dependsOn("updateManifestVersion")
    archiveClassifier.set("")  // No classifier, replaces main jar

    // Don't relocate SQLite - it has complex native loading that breaks with relocation
    // relocate("org.sqlite", "com.hyfixes.libs.sqlite")

    // Exclude SLF4J - Hytale has its own logging and SQLite's SLF4J causes classloader conflicts
    exclude("org/slf4j/**")
    exclude("META-INF/services/org.slf4j.*")
    exclude("META-INF/maven/org.slf4j/**")

    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
            "Plugin-Class" to "com.hyfixes.HyFixes"
        )
    }

    // Minimize the jar - only include used classes
    // But don't minimize SQLite as it's loaded via Class.forName()
    minimize {
        exclude(dependency("org.xerial:sqlite-jdbc:.*"))
    }
}

// Make build use shadowJar
tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
