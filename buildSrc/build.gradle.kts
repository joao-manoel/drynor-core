plugins {
    `kotlin-dsl`
}

dependencies {
    // groovy.json.JsonSlurper, from the Gradle distribution: the catalog reader needs no external
    // dependency and no network beyond what a Gradle build already has.
    implementation(localGroovy())

    // JUnit 4 rather than kotlin-test: kotlin-test would drag a second Kotlin stdlib in beside the
    // one the `kotlin-dsl` plugin pins.
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    testLogging {
        events("passed", "failed", "skipped")
    }
}
