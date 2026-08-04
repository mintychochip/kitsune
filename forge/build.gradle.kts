plugins {
    java
}

dependencies {
    implementation(project(":common"))
    implementation(project(":api"))
}

tasks.test {
    useJUnitPlatform()
}
