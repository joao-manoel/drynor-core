import org.darkan.build.gameval.GenerateGamevalConstants

plugins {
    kotlin("plugin.serialization")
    idea
    `maven-publish`
}

java {
    withSourcesJar()
}

dependencies {
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("com.google.guava:guava:33.7.1-jre")
    // Two reference tables in the 727 cache (255/2 and 255/9) were bzip2ed by commons-compress
    // rather than libbzip2, so reproducing them byte for byte needs its encoder as well as ours.
    implementation("org.apache.commons:commons-compress:1.28.0")
    testImplementation(kotlin("test"))

    // The battle-tested legacy server library, for the differential codec tests only. Kept out of
    // the main source set on purpose: nothing in production may depend on it. Its POM pulls a
    // parallel server stack (netty, undertow, okhttp, mongo, argon2) in at runtime scope, none of
    // which the tests need, so the transitive graph is dropped entirely and the jar is used alone.
    testImplementation(project(":legacy-compat"))
}

// Typed gameval constants (org.darkan.core.gameval.gen.*): one Java class of public static final
// int per <cache source tree>/gamevals/<type>.json, generated into build/generated/sources/gameval/java
// by the buildSrc task and compiled as part of this module. Nothing is committed and no python is
// involved; see docs/gameval.md, "Typed constants". The catalogs are the only copy of the names and
// live in the cache source tree (docs/cache-source.md): the cache-source submodule by default, or the
// directory named by -Pdarkan.cacheSource=<path> / the DARKAN_CACHE_SOURCE environment variable.
val cacheSourceDir: File = (findProperty("darkan.cacheSource") as String?
    ?: System.getenv("DARKAN_CACHE_SOURCE"))
    ?.let { rootProject.file(it) }
    ?: rootProject.file("cache-source")
val generateGamevalConstants = tasks.register<GenerateGamevalConstants>("generateGamevalConstants") {
    group = "generation"
    description = "Generates org.darkan.core.gameval.gen.* from the cache source tree's gamevals/*.json"
    catalogDirectory.set(File(cacheSourceDir, "gamevals"))
    outputDirectory.set(layout.buildDirectory.dir("generated/sources/gameval/java"))
    generatorLabel.set("the :core:generateGamevalConstants Gradle task (buildSrc org.darkan.build.gameval)")
    regenerateCommand.set("./gradlew :core:generateGamevalConstants")
}

sourceSets.main {
    java.srcDir(generateGamevalConstants.flatMap { it.outputDirectory })
}

// So a Gradle-imported IDE indexes the constants (and marks them generated rather than hand-written).
idea.module {
    generatedSourceDirs.add(layout.buildDirectory.dir("generated/sources/gameval/java").get().asFile)
}

publishing {
    val ciProjectId: String? = System.getenv("CI_PROJECT_ID")
    val ciJobToken: String? = System.getenv("CI_JOB_TOKEN")
    val ciPipelineId: String? = System.getenv("CI_PIPELINE_ID")
    val ciBranch: String? = System.getenv("CI_COMMIT_REF_SLUG")
    val ciTag: String? = System.getenv("CI_COMMIT_TAG")

    publications.create<MavenPublication>("library") {
        version = ciTag ?: if (ciBranch != null && ciPipelineId != null) "${project.version}-${ciBranch}-${ciPipelineId}" else project.version.toString()
        artifactId = "core"
        from(components["java"])
    }

    if (ciProjectId == null || ciJobToken == null) return@publishing
    repositories {
        maven {
            name = "GitLab"
            url = uri("https://gitlab.com/api/v4/projects/${ciProjectId}/packages/maven")
            credentials(HttpHeaderCredentials::class) {
                name = "Job-Token"
                value = ciJobToken
            }
            authentication {
                create<HttpHeaderAuthentication>("header")
            }
        }
    }
}
