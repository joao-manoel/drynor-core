package org.drynor.build.gameval

import org.gradle.testfixtures.ProjectBuilder
import java.io.File

/**
 * Catalog fixtures for the codegen tests: a temp `gamevals/` folder with exactly the types a test
 * cares about, run through the real Gradle task so a rejected catalog is proven to fail the build
 * and not merely to fail a helper.
 */
object Fixtures {

    /** Writes `<dir>/<type>.json` with [entries] and, unless overridden, a coverage block that matches them. */
    fun catalog(
        dir: File,
        type: String,
        entries: Map<String, String>,
        coverage: String? = null,
        rawEntries: String? = null,
    ): File {
        val named = entries.count { (key, name) -> name != placeholderName(type, key) }
        val body = rawEntries ?: entries.entries.joinToString(", ") { (key, name) -> "\"$key\": \"$name\"" }
        val cov = coverage ?: "{ \"cache_ids\": ${entries.size}, \"named\": $named, \"placeholder\": ${entries.size - named} }"
        val file = File(dir, "$type.json")
        file.writeText("{ \"revision\": 727, \"type\": \"$type\", \"coverage\": $cov, \"entries\": { $body } }")
        return file
    }

    /**
     * Runs the real [GenerateGamevalConstants] over [catalogDir] for [classes] and returns the
     * generated files by name. Throws exactly what the build would throw.
     */
    fun generate(catalogDir: File, classes: Map<String, String>, includePlaceholders: Boolean = true, shardSize: Int = 32000): Map<String, String> {
        val project = ProjectBuilder.builder().withProjectDir(catalogDir.parentFile).build()
        val out = File(catalogDir.parentFile, "out")
        val task = project.tasks.create("generateGamevalConstants", GenerateGamevalConstants::class.java)
        task.catalogDirectory.set(catalogDir)
        task.outputDirectory.set(out)
        task.classes.set(classes)
        task.includePlaceholders.set(includePlaceholders)
        task.shardSize.set(shardSize)
        task.generatorLabel.set("test")
        task.regenerateCommand.set("./gradlew :core:generateGamevalConstants")
        task.generate()
        val pkg = File(out, "org/drynor/core/gameval/gen")
        return pkg.listFiles().orEmpty().filter { it.name.endsWith(".java") }.associate { it.name to it.readText() }
    }

    /** The constants a generated file declares, in file order. */
    fun constants(source: String): List<Pair<String, Int>> =
        Regex("public static final int (\\w+) = (-?\\d+);").findAll(source)
            .map { it.groupValues[1] to it.groupValues[2].toInt() }
            .toList()
}
