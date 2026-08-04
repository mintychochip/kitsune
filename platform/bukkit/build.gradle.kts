plugins {
    `java-library`
}

java {
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    api(project(":api"))
    implementation(project(":common"))
    compileOnly(libs.spigot.api)
    testImplementation(platform("org.junit:junit-bom:${libs.versions.junit.get()}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly(libs.spigot.api)
}

tasks.test {
    useJUnitPlatform()
}
