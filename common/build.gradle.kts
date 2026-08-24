plugins {
    id("java-library")
}

dependencies {
    // API module
    api(project(":api"))

    // Google Guava for Preconditions
    api("com.google.guava:guava:33.0.0-jre")

    // ONNX Runtime for local embeddings
    api("com.microsoft.onnxruntime:onnxruntime:1.19.2")

    // DJL for BERT tokenization
    api("ai.djl:api:0.31.1")
    api("ai.djl.huggingface:tokenizers:0.31.1")

    // HikariCP for database connection pooling
    api("com.zaxxer:HikariCP:5.1.0")

    // Caffeine for high-performance caching with LRU eviction, TTL, and thread-safety
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    // JVector for vector similarity search
    api("io.github.jbellis:jvector:4.0.0-rc.6")

    // JDBC drivers for database backends (commented out - can re-enable if needed)
    // api("org.postgresql:postgresql:42.7.1")
    // api("com.mysql:mysql-connector-j:8.2.0")

    // Guice for dependency injection
    api("com.google.inject:guice:7.0.0")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
