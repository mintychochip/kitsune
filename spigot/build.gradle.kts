plugins {
    java
}

dependencies {
    implementation(project(":common"))
    implementation(project(":api"))
    implementation(project(":platform:bukkit"))
}

tasks.test {
    useJUnitPlatform()
}
