plugins {
    `java-library`
}

java {
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    api(project(":api"))
    testImplementation(platform("org.junit:junit-bom:${libs.versions.junit.get()}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
