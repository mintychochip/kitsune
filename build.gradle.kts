import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.configure


allprojects {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
        maven("https://maven.fabricmc.net/")
        maven("https://maven.minecraftforge.net/")
        maven("https://maven.neoforged.net/releases/")
    }
}

subprojects {
    group = rootProject.providers.gradleProperty("group").get()
    version = rootProject.providers.gradleProperty("version").get()

    pluginManager.withPlugin("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(21))
        }
        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.release.set(21)
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}
val publishedArtifacts = mapOf(
    ":api" to "kitsune-api",
    ":paper" to "kitsune-paper",
    ":spigot" to "kitsune-spigot",
    ":fabric" to "kitsune-fabric",
    ":forge" to "kitsune-forge",
    ":neoforge" to "kitsune-neoforge"
)

subprojects {
    if (path in publishedArtifacts) {
        apply(plugin = "maven-publish")
    }

    pluginManager.withPlugin("maven-publish") {
        afterEvaluate {
            extensions.configure<PublishingExtension> {
                publications {
                    create<MavenPublication>("mavenJava") {
                        artifactId = publishedArtifacts.getValue(project.path)
                        if (project.path == ":api") {
                            from(components["java"])
                        } else {
                            val mainArtifact = when (project.path) {
                                ":fabric" -> tasks.getByName("remapJar")
                                else -> tasks.findByName("shadowJar") ?: tasks.getByName("jar")
                            }
                            artifact(mainArtifact)
                            artifact(tasks.getByName("sourcesJar"))
                            artifact(tasks.getByName("javadocJar"))
                        }
                        pom {
                            name.set("Kitsune ${project.path.removePrefix(":").replaceFirstChar { it.uppercase() }}")
                            description.set("Semantic nearby storage search.")
                            licenses {
                                license {
                                    name.set("MIT")
                                    url.set("https://opensource.org/license/mit/")
                                }
                            }
                        }
                    }
                }

                val publicationUrl = providers.gradleProperty("publicationUrl")
                    .orElse(providers.environmentVariable("KITSUNE_MAVEN_URL"))
                if (publicationUrl.isPresent) {
                    repositories {
                        maven {
                            name = "configured"
                            url = uri(publicationUrl.get())
                            credentials {
                                username = providers.gradleProperty("publicationUsername")
                                    .orElse(providers.environmentVariable("KITSUNE_MAVEN_USERNAME"))
                                    .orNull
                                password = providers.gradleProperty("publicationPassword")
                                    .orElse(providers.environmentVariable("KITSUNE_MAVEN_PASSWORD"))
                                    .orNull
                            }
                        }
                    }
                }
            }
        }
    }
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()
