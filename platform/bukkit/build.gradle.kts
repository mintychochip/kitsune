plugins {
    `java-library`
}

repositories {
    maven("https://repo.codemc.io/repository/maven-public/")
}

java {
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    api(project(":kitsune-api"))
    implementation(project(":kitsune-common"))
    compileOnly(libs.spigot.api)
    compileOnly("org.popcraft:bolt-bukkit:1.1.52")
    testCompileOnly(libs.spigot.api)
    testCompileOnly("org.popcraft:bolt-bukkit:1.1.52")
    testImplementation(platform("org.junit:junit-bom:${libs.versions.junit.get()}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly(libs.spigot.api)
}

tasks.test {
    useJUnitPlatform()
}
