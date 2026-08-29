import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

allprojects {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
        exclusiveContent {
            forRepository {
                maven {
                    name = "utilitiesGitHubPackages"
                    url = uri("https://maven.pkg.github.com/mintychochip/Utilities")
                    credentials {
                        username = project.findProperty("gpr.user") as String?
                            ?: System.getenv("GITHUB_ACTOR")
                            ?: System.getenv("USERNAME")
                        password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
                    }
                }
            }
            filter {
                includeGroup("org.aincraft")
            }
        }
    }
}

subprojects {
    group = rootProject.providers.gradleProperty("group").get()
    version = rootProject.providers.gradleProperty("version").get()

    pluginManager.withPlugin("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(25))
        }
        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.release.set(25)
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}

val publishedArtifacts = mapOf(
    ":kitsune-api" to "kitsune-api",
    ":kitsune-paper" to "kitsune-paper"
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
                        if (project.path == ":kitsune-api") {
                            from(components["java"])
                        } else {
                            val mainArtifact = tasks.findByName("shadowJar") ?: tasks.getByName("jar")
                            artifact(mainArtifact)
                            artifact(tasks.getByName("sourcesJar"))
                            artifact(tasks.getByName("javadocJar"))
                            tasks.matching { task ->
                                task.name.startsWith("publishMavenJavaPublicationTo")
                            }.configureEach {
                                dependsOn(mainArtifact)
                            }
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
