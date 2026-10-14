plugins {
    kotlin("jvm")
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
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(26))
    }
    withSourcesJar()
}

repositories {
    mavenLocal()
    mavenCentral()
}

group = "rs.drynor"
version = "3.0.0-drynor-SNAPSHOT"

dependencies {
    implementation(project(":core"))
    implementation(project(":cs2"))
    implementation("com.google.code.gson:gson:2.12.1")
    implementation("com.google.guava:guava:33.4.0-jre")
    implementation("org.mongodb:mongodb-driver-sync:5.3.1")
    implementation("org.mongodb:mongodb-driver-core:5.3.1")
    implementation("io.netty:netty-all:4.1.117.Final")
    implementation("io.undertow:undertow-core:2.3.18.Final")
    implementation("io.github.classgraph:classgraph:4.8.180")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

tasks.register<JavaExec>("verifyPackedCache") {
    workingDir = rootDir
    group = "verification"
    description = "Builds source cache and checks legacy Store, interfaces, CS2 and item definitions."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.rs.cache.CacheCompatibilityProbe")
    args(providers.gradleProperty("cachePath").getOrElse("../cache/"))
}

tasks.register<JavaExec>("exportClientScriptFixtures") {
    group = "verification"
    dependsOn(tasks.named("classes"))
    workingDir = rootDir
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.rs.cache.ClientScriptFixtures")
    environment("DRYNOR_CACHE_BUILD", "false")
    args(providers.gradleProperty("cachePath").getOrElse("../cache/"), rootDir.resolve("build/client-script-fixtures").absolutePath)
}
