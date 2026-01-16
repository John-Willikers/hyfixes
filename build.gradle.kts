plugins {
    java
}

group = "com.hyfixes"
version = "1.0.0"

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

tasks.jar {
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
