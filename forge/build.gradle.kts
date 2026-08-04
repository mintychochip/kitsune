plugins {
    java
    id("net.minecraftforge.gradle") version "7.0.3"
}
java {
    withSourcesJar()
    withJavadocJar()
}

tasks.jar {
    dependsOn(project(":api").tasks.named("classes"), project(":common").tasks.named("classes"))
    from(project(":api").sourceSets.main.get().output)
    from(project(":common").sourceSets.main.get().output)
    project(":common").configurations.getByName("runtimeClasspath")
        .resolve()
        .filter { it.name.startsWith("sqlite-jdbc-") }
        .forEach { from(zipTree(it)) }
}

minecraft {
    mappings("official", "1.21.4")
}

repositories {
    minecraft.mavenizer(this)
}

dependencies {
    implementation(project(":common"))
    implementation(project(":api"))
    implementation(minecraft.dependency("net.minecraftforge:forge:${rootProject.libs.versions.forge.get()}").get())
    testImplementation(minecraft.dependency("net.minecraftforge:forge:${rootProject.libs.versions.forge.get()}").get())
    compileOnly("net.minecraftforge:javafmllanguage:${rootProject.libs.versions.forge.get()}")
    compileOnly("net.minecraftforge:eventbus:6.2.27")
    testRuntimeOnly("net.minecraftforge:javafmllanguage:${rootProject.libs.versions.forge.get()}")
    testRuntimeOnly("net.minecraftforge:eventbus:6.2.27")
    testImplementation(platform("org.junit:junit-bom:${rootProject.libs.versions.junit.get()}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    filesMatching("META-INF/mods.toml") {
        expand("version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
}
