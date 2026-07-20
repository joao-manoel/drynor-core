plugins {
    `java-library`
    `maven-publish`
    id("com.github.ben-manes.versions") version "0.52.0"
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/joao-manoel/gielenor-core")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                    ?: providers.gradleProperty("gpr.user").orNull
                password = System.getenv("GITHUB_TOKEN")
                    ?: providers.gradleProperty("gpr.key").orNull
            }
        }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(24))
    }
    withSourcesJar()
}

repositories {
    mavenLocal()
    mavenCentral()
}

group = "rs.drynor"
version = "2.0.5"

dependencies {
    implementation("com.google.code.gson:gson:2.12.1")
    implementation("com.google.guava:guava:33.4.0-jre")
    implementation("org.mongodb:mongodb-driver-sync:5.3.1")
    implementation("org.mongodb:mongodb-driver-core:5.3.1")
    implementation("io.netty:netty-all:4.1.117.Final")
    implementation("io.undertow:undertow-core:2.3.18.Final")
    implementation("io.github.classgraph:classgraph:4.8.180")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
