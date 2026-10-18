import org.gradle.api.plugins.jvm.JvmTestSuite
import org.gradle.testing.base.TestingExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    kotlin("jvm") version "2.4.20" apply false
    kotlin("plugin.serialization") version "2.4.20" apply false
}

allprojects {
    group = "rs.drynor"
    version = findProperty("drynorVersion") as String

    repositories {
        mavenLocal()
        mavenCentral()
    }
}

val testBudgetSeconds = (findProperty("drynor.testBudgetSeconds") as String?)?.toDouble() ?: 20.0

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    configure<PublishingExtension> {
        if (project.name == "cs2") {
            publications.create<MavenPublication>("library") {
                from(components["java"])
            }
        }
        repositories.maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/joao-manoel/drynor-core")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: providers.gradleProperty("gpr.user").orNull
                password = System.getenv("GITHUB_TOKEN") ?: providers.gradleProperty("gpr.key").orNull
            }
        }
    }

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(26))
        }
    }

    // Two suites per module. `test` is the fast one every build runs, held to a time budget of
    // test time (the JUnit XML sum, JVM start-up excluded). `cacheTest` (src/cacheTest) holds
    // everything that opens the packed cache or a source tree: whole-cache round trips, encoder and
    // codec parity. Minutes long, opt-in only - nothing in build, check or CI runs it.
    tasks.withType<Test>().configureEach {
        environment("GAMEVAL_PATH", rootDir.resolve("../cache-unpacked/gamevals").canonicalPath)
        workingDir = rootDir
        maxHeapSize = "4g"
        testLogging.showStandardStreams = true
        // A test never requires a packed cache: without one the world boots on empty definition
        // tables instead of dying, and `cacheTest` says so itself by failing its own assertions.
        systemProperty("drynor.cache.optional", "true")
    }

    if (file("src/cacheTest").isDirectory) {
        configure<TestingExtension> {
            suites.register<JvmTestSuite>("cacheTest") {
                useJUnit()
                dependencies {
                    implementation(project())
                }
                val unit = project.the<SourceSetContainer>()["test"]
                configurations[sources.implementationConfigurationName].extendsFrom(configurations[unit.implementationConfigurationName])
                configurations[sources.runtimeOnlyConfigurationName].extendsFrom(configurations[unit.runtimeOnlyConfigurationName])
                project.dependencies.add(sources.implementationConfigurationName, unit.output)
            }
        }
        configure<KotlinJvmProjectExtension> {
            target.compilations.named("cacheTest") {
                associateWith(target.compilations.getByName("main"))
                associateWith(target.compilations.getByName("test"))
            }
        }
    }

    tasks.named<Test>("test") {
        val budget = testBudgetSeconds
        val results = reports.junitXml.outputLocation
        doLast {
            val suites = results.get().asFile.listFiles { f -> f.extension == "xml" }.orEmpty().map { file ->
                val suite = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
                suite.getAttribute("name") to suite.getAttribute("time").toDouble()
            }
            val total = suites.sumOf { it.second }
            if (total > budget) {
                val slowest = suites.sortedByDescending { it.second }.take(5)
                    .joinToString("\n") { (name, seconds) -> "  %6.1fs  %s".format(seconds, name) }
                throw GradleException(
                    "$path ran for %.1fs of test time; the budget is %.0fs (-Pdrynor.testBudgetSeconds). ".format(total, budget) +
                        "Make the slow tests fast or move them to src/cacheTest, the opt-in suite:\n$slowest",
                )
            }
        }
    }

    tasks.withType<Jar> {
        manifest {
            attributes(
                mapOf(
                    "Implementation-Title" to project.name,
                    "Implementation-Version" to project.version
                )
            )
        }
    }

    dependencies {
        val implementation = configurations["implementation"]

        //Kotlin
        implementation("org.jetbrains.kotlin:kotlin-stdlib:${findProperty("ktVersion")}")
        implementation("org.jetbrains.kotlin:kotlin-stdlib-common:${findProperty("ktVersion")}")
        implementation("org.jetbrains.kotlin:kotlin-scripting-common:${findProperty("ktVersion")}")
        implementation("org.jetbrains.kotlin:kotlin-scripting-jvm:${findProperty("ktVersion")}")
        implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host:${findProperty("ktVersion")}")
        implementation("org.jetbrains.kotlin:kotlin-main-kts:${findProperty("ktVersion")}")
        implementation("org.jetbrains.kotlin:kotlin-script-runtime:${findProperty("ktVersion")}")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

        //Ktor
        implementation("io.ktor:ktor-server-core-jvm:${findProperty("ktorVersion")}")
        implementation("io.ktor:ktor-network-jvm:${findProperty("ktorVersion")}")
        implementation("io.ktor:ktor-server-netty-jvm:${findProperty("ktorVersion")}")
        implementation("io.ktor:ktor-server-websockets:${findProperty("ktorVersion")}")
        implementation("io.ktor:ktor-server-sessions:${findProperty("ktorVersion")}")
        implementation("io.ktor:ktor-server-content-negotiation:${findProperty("ktorVersion")}")
        implementation("io.ktor:ktor-server-status-pages:${findProperty("ktorVersion")}")
        implementation("io.ktor:ktor-server-request-validation:${findProperty("ktorVersion")}")
        implementation("io.ktor:ktor-serialization-kotlinx-json:${findProperty("ktorVersion")}")
        implementation("io.ktor:ktor-client-core:${findProperty("ktorVersion")}")
        implementation("io.ktor:ktor-client-cio:${findProperty("ktorVersion")}")
        implementation("io.ktor:ktor-client-websockets:${findProperty("ktorVersion")}")
        implementation("io.ktor:ktor-client-content-negotiation:${findProperty("ktorVersion")}")

        implementation("de.mkammerer:argon2-jvm:2.12")
        implementation("io.github.cdimascio:dotenv-kotlin:6.5.1")
        implementation("org.mongodb:mongodb-driver-kotlin-coroutine:5.11.1")
        implementation("com.github.jponge:lzma-java:1.3")

        implementation("org.mongodb:mongodb-driver-sync:5.11.1")
        implementation("org.mongodb:mongodb-driver-core:5.11.1")
        implementation("it.unimi.dsi:fastutil:8.5.19")
        implementation("com.trivago:fastutil-concurrent-wrapper:0.3.0")

        implementation("io.github.classgraph:classgraph:4.8.195")
    }
}
