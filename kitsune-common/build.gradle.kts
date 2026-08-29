plugins {
    `java-library`
}

java {
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    api(project(":kitsune-api"))
    implementation("org.xerial:sqlite-jdbc:${libs.versions.sqlite.get()}")
    implementation("org.aincraft:utilities-db-sql:2026.08.27")
    implementation(libs.jackson.databind)
    implementation("com.microsoft.onnxruntime:onnxruntime:1.19.2")
    implementation("ai.djl:api:0.31.1")
    implementation("ai.djl.huggingface:tokenizers:0.31.1")
    testImplementation(platform("org.junit:junit-bom:${libs.versions.junit.get()}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
