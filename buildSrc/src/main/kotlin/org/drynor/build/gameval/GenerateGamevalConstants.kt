package org.drynor.build.gameval

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Generates `org.drynor.core.gameval.gen.*` - one Java class of `public static final int` per
 * `gamevals/<type>.json` - into [outputDirectory], which `core`'s main source set compiles.
 *
 * The task is incremental and cacheable: it re-runs only when a catalog's content, one of the
 * inputs below, or the generator code itself changes (Gradle hashes the task's implementation
 * classpath, which is this `buildSrc` output).
 *
 * A catalog that cannot produce sound constants fails the build; see [validateCatalog].
 */
@CacheableTask
abstract class GenerateGamevalConstants : DefaultTask() {

    /** The folder holding `<type>.json`; the cache source tree's `gamevals/`. */
    @get:Internal
    abstract val catalogDirectory: DirectoryProperty

    /** The catalogs themselves - the content that decides whether this task must re-run. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val catalogFiles: ConfigurableFileCollection

    /** Catalog type -> generated class name. */
    @get:Input
    abstract val classes: MapProperty<String, String>

    @get:Input
    abstract val packageName: Property<String>

    /** `false` emits named ids only, the way the retired python generator did. */
    @get:Input
    abstract val includePlaceholders: Property<Boolean>

    /** Constants per class before the package-private superclass chain continues. */
    @get:Input
    abstract val shardSize: Property<Int>

    /** Named in every generated file's first line. */
    @get:Input
    abstract val generatorLabel: Property<String>

    /** Named in every generated class's javadoc. */
    @get:Input
    abstract val regenerateCommand: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    init {
        classes.convention(GAMEVAL_CLASSES)
        packageName.convention("org.drynor.core.gameval.gen")
        includePlaceholders.convention(true)
        shardSize.convention(32000)
        generatorLabel.convention("buildSrc GenerateGamevalConstants")
        regenerateCommand.convention("./gradlew :core:generateGamevalConstants")
        catalogFiles.from(catalogDirectory.map { dir -> classes.get().keys.map { dir.file("$it.json").asFile } })
    }

    @TaskAction
    fun generate() {
        val catalogDir = catalogDirectory.get().asFile
        val codegen = GamevalCodegen(
            generatorLabel = generatorLabel.get(),
            regenerateCommand = regenerateCommand.get(),
            includePlaceholders = includePlaceholders.get(),
            packageName = packageName.get(),
            shardSize = shardSize.get(),
        )
        val target = File(outputDirectory.get().asFile, packageName.get().replace('.', '/'))
        target.mkdirs()

        val produced = LinkedHashMap<String, String>()
        val report = ArrayList<String>()
        var constants = 0
        var rewrites = 0
        val catalogs = LinkedHashMap<String, Catalog>()

        for ((type, cls) in classes.get()) {
            val catalog = readCatalog(type, File(catalogDir, "$type.json"))
            catalogs[type] = catalog
            val result = codegen.generate(catalog)
            for ((name, content) in result.files) {
                val clash = produced.put(name, content)
                if (clash != null) throw GamevalGenerationException("two catalogs both generate $name; give one of them a different class name")
            }
            constants += result.constants
            rewrites += result.rewrites.size
            report.add(
                "%-18s %-18s %7d constants  %d file(s)  %d rewrite(s)%s"
                    .format(type, cls, result.constants, result.files.size, result.rewrites.size,
                        if (result.files.size > 1) "  [${result.files.keys.sorted().joinToString(", ")}]" else ""),
            )
        }
        val component = catalogs[COMPONENT]
        val interfaces = catalogs["interface"]
        if (component != null && interfaces != null) validateInterfaceNamesAgree(component, interfaces)

        var written = 0
        for ((name, content) in produced) {
            val file = File(target, name)
            if (file.isFile && file.readText() == content) continue
            file.writeText(content)
            written++
        }
        var removed = 0
        for (file in target.listFiles().orEmpty()) {
            if (file.name.endsWith(".java") && file.name !in produced) {
                file.delete()
                removed++
            }
        }

        for (line in report) logger.info("  {}", line)
        logger.lifecycle(
            "gameval constants: {} constants over {} types in {} file(s) ({} identifier rewrite(s), {} written, {} removed) -> {}",
            constants, classes.get().size, produced.size, rewrites, written, removed, target,
        )
    }
}
