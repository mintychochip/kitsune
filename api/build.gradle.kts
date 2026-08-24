plugins {
    id("java-library")
}

dependencies {
    // Pure Java - no platform dependencies
    api(platform("net.kyori:adventure-bom:4.17.0"))
    api("net.kyori:adventure-api")
    api("net.kyori:adventure-text-minimessage:4.26.1")
    api("com.google.guava:guava:33.0.0-jre")
    api("com.google.code.gson:gson:2.10.1")

    api("org.jetbrains:annotations:26.1.0")
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
