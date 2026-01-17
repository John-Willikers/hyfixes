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
    // Hytale Server API (for ClassTransformer interface)
    compileOnly(files("libs/HytaleServer.jar"))

    // ASM for bytecode manipulation
    implementation("org.ow2.asm:asm:9.6")
    implementation("org.ow2.asm:asm-commons:9.6")
    implementation("org.ow2.asm:asm-util:9.6")
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "HyFixes Early Plugin",
            "Implementation-Version" to project.version
        )
    }

    // Include ASM dependencies in the JAR (fat jar)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    archiveBaseName.set("hyfixes-early")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
