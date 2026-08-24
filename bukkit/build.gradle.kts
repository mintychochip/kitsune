plugins {
    id("java")
    id("com.gradleup.shadow")
    id("xyz.jpenilla.run-paper")
}

version = rootProject.version

repositories {
    maven("https://repo.codemc.io/repository/maven-public/")
    maven {
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}
dependencies {
    compileOnly("org.popcraft:bolt-bukkit:1.1.52")
    implementation(project(":api"))
    implementation(project(":common"))

    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Guice for dependency injection (inherits from common)
    implementation("com.google.inject:guice:7.0.0")
}

tasks.jar {
    archiveFileName.set("ChestFind-${archiveVersion.get()}.jar")
    manifest {
        attributes("Manifest-Version" to "1.0")
    }
}

tasks.runServer {
    minecraftVersion("1.21.11")

    downloadPlugins {
        modrinth("bolt", "1.1.52")
    }
}

tasks.shadowJar {
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")

    // Merge service files
    mergeServiceFiles()

    // Relocate Guice to avoid conflicts
    relocate("com.google.inject", "org.aincraft.kitsune.lib.guice")
    relocate("jakarta.inject", "org.aincraft.kitsune.lib.jakarta.inject")

    // Exclude unused large dependencies
    exclude("io/milvus/**")
    exclude("io/grpc/**")
    exclude("com/amazonaws/**")
    exclude("com/azure/**")
    exclude("org/apache/arrow/**")
    exclude("org/apache/parquet/**")
    exclude("shaded/parquet/**")
}
