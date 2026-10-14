package org.darkan.cs2

import org.darkan.core.gameval.Gameval
import org.darkan.core.gameval.GamevalCatalog
import org.darkan.cs2.compile.Cs2Lexer
import org.darkan.cs2.compile.Token
import org.darkan.cs2.compile.TokenKind
import java.io.File

/**
 * Keeps a decompiled folder and the gameval catalogs saying the same thing.
 *
 * `gamevals/<type>.json` is the only place a name lives. Everything in a
 * decompiled folder - the function names, `vars.d.ts`, `gamevals.d.ts`, every
 * identifier in every script - is derived from it plus the id each name is bound
 * to. Two commands move a change between the two, and nothing else may:
 *
 *  - [apply] re-derives the folder from the catalogs. Edit a name in the JSON,
 *    run it, and every declaration and every reference in every script is
 *    rewritten to match. This is the direction that makes a catalog edit safe: a
 *    name that changed there cannot leave a stale identifier behind.
 *  - [sync] writes an editor rename back into the catalogs, then immediately
 *    re-derives, so the two can never be left disagreeing once it returns.
 *
 * Neither runs on its own. A compile that finds the two disagreeing about what a
 * name means refuses and says which command reconciles them.
 */
object Cs2NameSync {

    /** One name that moved, for the report a command prints. */
    class Change(val what: String, val key: String, val from: String, val to: String) {
        override fun toString(): String = "$what $key: $from -> $to"
    }

    class ApplyReport(
        val declarations: List<String>,
        val changes: List<Change>,
        val filesRewritten: Int,
        val filesMoved: Int,
        val filesScanned: Int,
        val problems: List<String>,
    ) {
        val ok: Boolean get() = problems.isEmpty()
    }

    class SyncReport(
        val catalogs: Map<String, Int>,
        val changes: List<Change>,
        val refusals: List<String>,
        val applied: ApplyReport?,
    ) {
        val ok: Boolean get() = refusals.isEmpty()
    }

    // ------------------------------------------------------------------ apply

    /**
     * Re-derives every generated file and every identifier in [folder] from the
     * catalogs.
     *
     * The bindings - a script's `// clientscript <id>` header, the id in each
     * `vars.d.ts` and `gamevals.d.ts` doc comment - are what says which id a name
     * currently stands for. Whatever the catalogs call that id now is what the
     * folder is rewritten to.
     */
    fun apply(folder: File): ApplyReport {
        val problems = ArrayList<String>()
        if (!folder.isDirectory) {
            return ApplyReport(emptyList(), emptyList(), 0, 0, 0, listOf("no such folder: ${folder.absolutePath}"))
        }
        val symbols = Cs2SymbolTable.read(folder)
        return apply(folder, symbols, symbols.scriptFiles)
    }

    /**
     * @param bindings relative path -> script id, as read *before* any catalog was
     * written. After a rename a file's header and its path both still say the old
     * name, so this is what says which script each one is; a standalone [apply]
     * reads it from the folder, and [sync] hands over what it saw first.
     */
    private fun apply(folder: File, symbols: Cs2SymbolTable, bindings: Map<String, Int>): ApplyReport {
        val problems = ArrayList<String>()
        val files = Cs2Layout.scriptFiles(folder)
        val resolved = resolve(folder, files, bindings, problems)
        // Script renames are read from what each file declares rather than from the
        // symbol table: after a catalog edit a file's header names a script that no
        // longer exists, so the table skipped it, and it is exactly those files
        // whose function has to be renamed.
        val bound = symbols.renames.filter { it.what != Gameval.CLIENTSCRIPT } + scriptRenames(resolved)
        val renames = renamesFrom(bound)
        val changes = bound.map { Change(it.what, it.key, it.project, it.catalog) }

        val declarations = ArrayList<String>()
        folder.resolve(Cs2Declarations.OPCODES_FILE).writeText(Cs2Declarations.generate())
        declarations.add(Cs2Declarations.OPCODES_FILE)
        folder.resolve(Cs2Declarations.GAMEVALS_FILE).writeText(Cs2Declarations.gamevals())
        declarations.add(Cs2Declarations.GAMEVALS_FILE)
        folder.resolve("tsconfig.json").writeText(Cs2Declarations.tsconfig())
        declarations.add("tsconfig.json")

        // `vars.d.ts` holds the variables the corpus actually touches, which only a
        // whole-cache decompile knows; its own bindings are that list, so it is
        // re-emitted from them rather than rebuilt.
        val variables = symbols.variableBindings().values.distinct()
        if (variables.isNotEmpty()) {
            folder.resolve(Cs2Declarations.VARS_FILE).writeText(Cs2Declarations.variables(variables))
            declarations.add(Cs2Declarations.VARS_FILE)
        }

        var rewritten = 0
        var moved = 0
        for (file in files) {
            val id = resolved[file] ?: continue
            val source = try {
                file.readText()
            } catch (e: Exception) {
                problems.add("cannot read ${Cs2Layout.relativePath(folder, file) ?: file.name}: ${e.message}")
                continue
            }
            val updated = header(rewrite(source, renames), id)
            if (updated != source) {
                file.writeText(updated)
                rewritten++
            }
            // A script's name decides where it lives, so a catalog rename moves the
            // file as well as the text in it - leaving it under the old name would
            // be exactly the stale reference this command exists to remove.
            if (Cs2Layout.move(file, Cs2Layout.fileFor(folder, id))) moved++
        }
        return ApplyReport(declarations, changes, rewritten, moved, files.size, problems)
    }

    /** The function each file declares, where it is not the name the catalogs derive. */
    private fun scriptRenames(resolved: Map<File, Int>): List<Cs2SymbolTable.Rename> {
        val out = ArrayList<Cs2SymbolTable.Rename>()
        for ((file, id) in resolved) {
            val name = try {
                FUNCTION.find(file.readText())?.groupValues?.get(1)
            } catch (e: Exception) {
                null
            } ?: continue
            val derived = Cs2Names.scriptIdentifier(id)
            if (derived != name) {
                out.add(Cs2SymbolTable.Rename(Gameval.CLIENTSCRIPT, id.toString(), derived, name))
            }
        }
        return out
    }

    /**
     * Which script each file is, including the ones a catalog rename has orphaned.
     *
     * A file whose header names a script the catalogs no longer have has nothing
     * left to bind it - the path says the old name too. Where exactly one file is
     * orphaned and exactly one script has no file, the pairing is forced rather
     * than guessed: every other script is already accounted for, so the leftovers
     * can only be each other. Anything less clear-cut is reported and left alone,
     * because moving a file onto the wrong archive id is worse than not moving it.
     */
    private fun resolve(
        folder: File,
        files: List<File>,
        bindings: Map<String, Int>,
        problems: MutableList<String>,
    ): Map<File, Int> {
        val resolved = HashMap<File, Int>(files.size * 2)
        val orphans = ArrayList<File>()
        for (file in files) {
            val relative = Cs2Layout.relativePath(folder, file)
            val id = bindings[relative] ?: try {
                Cs2Layout.scriptIdOf(file.readText(), relative)
            } catch (e: Exception) {
                null
            }
            if (id == null) orphans.add(file) else resolved[file] = id
        }
        if (orphans.isEmpty()) return resolved

        val missing = Cs2Names.scriptPaths().keys - resolved.values.toSet()
        if (orphans.size == 1 && missing.size == 1) {
            resolved[orphans.first()] = missing.first()
            return resolved
        }
        for (orphan in orphans) {
            val relative = Cs2Layout.relativePath(folder, orphan) ?: orphan.name
            problems.add(
                "$relative: nothing in gamevals/clientscript.json is called " +
                    "'${headerNameOf(orphan) ?: relative}', and ${missing.size} scripts have no file, " +
                    "so which one this is cannot be settled - left where it is",
            )
        }
        return resolved
    }

    private fun headerNameOf(file: File): String? = try {
        Cs2Layout.gamevalHeader(file.readText())
    } catch (e: Exception) {
        null
    }

    /**
     * Brings a file's header back in line with the catalog.
     *
     * The header is the script's gameval name, which is what the catalog turns
     * into an archive id, and the triple-slash references have to walk out of
     * whatever category folder the file has just been moved into. Neither is an
     * identifier, so the token rewrite cannot reach them; both are re-derived from
     * the id the file resolved to.
     */
    private fun header(source: String, scriptId: Int): String {
        var out = source
        val binding = "// ${Cs2Layout.headerOf(scriptId)}"
        val existing = GAMEVAL_HEADER.find(out)
        out = when {
            existing != null -> out.replaceRange(existing.range, binding)
            // An older dump binds by number instead; replacing it in place is what
            // brings that folder onto the gameval layout.
            else -> NUMERIC_HEADER.find(out)?.let { out.replaceRange(it.range, binding) } ?: out
        }
        val up = Cs2Layout.referencePrefix(scriptId)
        return REFERENCE.replace(out) { match -> "/// <reference path=\"$up${match.groupValues[1]}\" />" }
    }

    // ------------------------------------------------------------------- sync

    /**
     * Writes the folder's renames into the catalogs, then re-derives the folder.
     *
     * The JSON write is the whole of the change: a catalog is edited in place, one
     * entry's name at a time, with its shape and every other entry untouched.
     * Everything after it is re-derivation, so the command cannot return with the
     * project and the catalogs disagreeing.
     *
     * A rename is refused rather than guessed at when the new name would not
     * derive back to the identifier the developer typed, when it collides with
     * another id's name or placeholder, or when it is a `Component.<interface>`
     * whose name is shared by every component under it - those are reported and
     * nothing at all is written.
     */
    fun sync(folder: File, catalogs: File, dryRun: Boolean = false): SyncReport {
        val refusals = ArrayList<String>()
        if (!folder.isDirectory) {
            return SyncReport(emptyMap(), emptyList(), listOf("no such folder: ${folder.absolutePath}"), null)
        }
        if (!catalogs.isDirectory) {
            return SyncReport(emptyMap(), emptyList(), listOf("no such catalog folder: ${catalogs.absolutePath}"), null)
        }
        val symbols = Cs2SymbolTable.read(folder)
        // Captured now: writing a catalog is what makes every header stale, and
        // this is what still knows which script each file was.
        val bindings = symbols.scriptFiles
        for (conflict in symbols.conflicts) refusals.add("$conflict")

        // type -> (entry key -> new name), collected before anything is written so
        // a refusal leaves every catalog exactly as it was.
        val edits = LinkedHashMap<String, LinkedHashMap<String, String>>()
        fun edit(type: String, key: String, name: String) {
            edits.getOrPut(type) { LinkedHashMap() }[key] = name
        }

        // A component's dev-name is `<interface>:<component>`, and the two halves
        // are renamed independently - the nested namespace moves every component
        // of the interface, one member moves only itself. They are collected apart
        // and joined at the end, so neither can overwrite the other.
        val components = Components()

        val changes = ArrayList<Change>()
        for (rename in symbols.renames) {
            val result = plan(rename, components, ::edit)
            if (result == null) {
                refusals.add("${rename.what} ${rename.key}: cannot write '${rename.project}' back to a catalog")
                continue
            }
            if (result.startsWith("!")) {
                refusals.add(result.substring(1))
                continue
            }
            changes.add(Change(rename.what, rename.key, rename.catalog, rename.project))
        }
        components.materialise(::edit)

        for ((type, entries) in edits) {
            validate(type, entries, refusals)
        }
        if (refusals.isNotEmpty() || dryRun) return SyncReport(emptyMap(), changes, refusals, null)

        val written = LinkedHashMap<String, Int>()
        for ((type, entries) in edits) {
            val file = catalogs.resolve("$type.json")
            val applied = try {
                writeCatalog(file, entries)
            } catch (e: Exception) {
                refusals.add("cannot write ${file.path}: ${e.message}")
                continue
            }
            written[type] = applied
        }
        if (refusals.isNotEmpty()) return SyncReport(written, changes, refusals, null)

        // The catalogs have moved, so every derivation is stale until it is thrown
        // away; re-deriving straight after is what stops the two diverging.
        Cs2Names.reload()
        // Re-read rather than reuse: against the new catalogs the names just
        // written are no longer renames, and rewriting from the old reading would
        // undo the very edit that was just saved. Only the file bindings are
        // carried over, because those the write is what invalidated.
        return SyncReport(written, changes, refusals, apply(folder, Cs2SymbolTable.read(folder), bindings))
    }

    /**
     * Works out which catalog entries a rename changes, or returns null when it is
     * one no catalog can hold. A message beginning `!` is a refusal to report.
     */
    private fun plan(
        rename: Cs2SymbolTable.Rename,
        components: Components,
        edit: (String, String, String) -> Unit,
    ): String? {
        // The old-variable flavour addresses the same id under the same name, so
        // its `Old` marker is not part of what the catalog stores.
        val project = rename.project.removeSuffix(OLD_SUFFIX)
        val space = Cs2Names.VAR_PREFIXES.entries.firstOrNull { it.value == rename.what }?.key
        if (space != null) {
            val type = Cs2Names.catalogType(space)
                ?: return "!${rename.what} ${rename.key}: there is no ${rename.what} catalog to rename in"
            val name = project.removePrefix("${rename.what}_")
            legal(type, rename.key, name)?.let { return "!$it" }
            edit(type, rename.key, name)
            return name
        }
        if (rename.what == Gameval.CLIENTSCRIPT) return script(rename, project, edit)
        if (rename.what == Gameval.COMPONENT) return component(rename, project, components)
        if (rename.what in Cs2Names.NAMESPACES) {
            val name = project.substringAfterLast('.')
            legal(rename.what, rename.key, name)?.let { return "!$it" }
            edit(rename.what, rename.key, name)
            return name
        }
        return null
    }

    /**
     * A clientscript keeps its `[category,...]` wrapper where it had one, so a
     * rename does not quietly drop Jagex's own grouping. The bare name has to stay
     * the one the identifier derives from, which it will not if some other
     * category already uses it - that is a refusal, not a silent `category_name`.
     */
    private fun script(
        rename: Cs2SymbolTable.Rename,
        project: String,
        edit: (String, String, String) -> Unit,
    ): String {
        val id = rename.key.toIntOrNull() ?: return "!clientscript ${rename.key}: not an id"
        legal(Gameval.CLIENTSCRIPT, rename.key, project)?.let { return "!$it" }
        val existing = Gameval.rawName(Gameval.CLIENTSCRIPT, id).orEmpty()
        val match = CLIENTSCRIPT_NAME.matchEntire(existing)
        // A script keeps the category it already had. One that never had a category
        // - a placeholder being named for the first time - takes the folder it has
        // been filed under, so moving the file into `proc/` is how it is put there;
        // left in the uncategorised folder it stays a bare name.
        val folder = rename.location?.substringBefore('/')?.takeIf { it.contains('/').not() }
        val category = match?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
            ?: folder?.takeIf { it != Cs2Names.UNCATEGORISED && it != rename.location }
        val name = if (category == null) project else "[$category,$project]"
        val others = Gameval.entries(Gameval.CLIENTSCRIPT).entries.count { (other, raw) ->
            other != id && CLIENTSCRIPT_NAME.matchEntire(raw)?.groupValues?.get(2) == project
        }
        if (category != null) legal(Gameval.CLIENTSCRIPT, rename.key, category)?.let { return "!$it" }
        if (others > 0) {
            return "!clientscript $id: another category already has a script called '$project', " +
                "so the name would derive as '<category>_$project' rather than '$project'"
        }
        edit(Gameval.CLIENTSCRIPT, rename.key, name)
        return name
    }

    /**
     * A component's dev-name is `<interface>:<component>`, so renaming the nested
     * namespace renames the interface half of every component under it - and the
     * interface's own catalog entry with it, which the build cross-checks.
     */
    private fun component(
        rename: Cs2SymbolTable.Rename,
        project: String,
        components: Components,
    ): String? {
        val parts = project.split('.')
        val derived = rename.catalog.split('.')
        if (parts.size != 3 || derived.size != 3) return null
        val interfaceId = rename.key.substringBefore(':').toIntOrNull() ?: return null
        val existing = Gameval.rawEntries(Gameval.COMPONENT)[rename.key] ?: return null
        if (!existing.contains(':')) {
            return "!component ${rename.key}: '$existing' has no interface part to rename under"
        }
        val newComponent = parts[2]
        legal(Gameval.COMPONENT, rename.key, newComponent)?.let { return "!$it" }
        components.member(rename.key, newComponent)
        // The interface half only moves when the developer actually renamed the
        // nested namespace; otherwise the catalog's own spelling is kept.
        if (parts[1] != derived[1]) {
            legal(Gameval.INTERFACE, interfaceId.toString(), parts[1])?.let { return "!$it" }
            components.group(interfaceId, existing.substringBefore(':'), parts[1])
        }
        return "$newComponent"
    }

    /**
     * The two halves of a component dev-name, gathered before either is written.
     *
     * Renaming the nested namespace touches every component of the interface and
     * renaming one member touches only itself; writing each as it is planned would
     * let whichever came last win. Collecting the halves and joining them once is
     * what makes both survive.
     */
    private class Components {
        /** `<iface>:<comp>` -> the component half the developer chose. */
        private val members = HashMap<String, String>()

        /** interface id -> its new name. */
        private val groups = HashMap<Int, String>()

        /** interface id -> the name it had, which says whether `interface.json` moves too. */
        private val previous = HashMap<Int, String>()

        fun member(key: String, name: String) {
            members[key] = name
        }

        fun group(interfaceId: Int, from: String, to: String) {
            groups[interfaceId] = to
            previous[interfaceId] = from
        }

        /** Emits `<interface>:<component>` for every entry either half touches. */
        fun materialise(edit: (String, String, String) -> Unit) {
            if (members.isEmpty() && groups.isEmpty()) return
            val catalog = Gameval.rawEntries(Gameval.COMPONENT)
            val keys = LinkedHashSet(members.keys)
            for (interfaceId in groups.keys) {
                for ((key, name) in catalog) {
                    if (key.substringBefore(':').toIntOrNull() != interfaceId) continue
                    if (!name.contains(':')) continue
                    keys.add(key)
                }
            }
            for (key in keys.sorted()) {
                val existing = catalog[key] ?: continue
                val interfaceId = key.substringBefore(':').toIntOrNull() ?: continue
                val group = groups[interfaceId] ?: existing.substringBefore(':')
                val member = members[key] ?: existing.substringAfter(':')
                edit(Gameval.COMPONENT, key, "$group:$member")
            }
            for ((interfaceId, name) in groups) {
                if (Gameval.rawName(Gameval.INTERFACE, interfaceId) != previous[interfaceId]) continue
                edit(Gameval.INTERFACE, interfaceId.toString(), name)
            }
        }
    }

    /**
     * Why [name] cannot be written to a catalog, or null when it can.
     *
     * The bar is the one the catalogs already hold themselves: a name has to be an
     * identifier as it stands - anything the escaping would have to change would
     * not derive back to what the developer typed - and it may not be another id's
     * placeholder, which would make that placeholder mean this id.
     */
    private fun legal(type: String, key: String, name: String): String? {
        if (name.isEmpty()) return "$type $key: an empty name"
        if (Cs2Identifiers.escape(name, key) != name) {
            return "$type $key: '$name' is not a name a catalog can hold as written; " +
                "it would come back as '${Cs2Identifiers.escape(name, key)}'"
        }
        val prefix = GamevalCatalog.placeholderPrefix(type)
        if (name != GamevalCatalog.placeholder(type, key) &&
            name.startsWith("${prefix}_") &&
            PLACEHOLDER_TAIL.matches(name.removePrefix("${prefix}_"))
        ) {
            return "$type $key: '$name' is the placeholder form of a different id"
        }
        return null
    }

    /** Rejects an edit that would leave a catalog with two ids under one name. */
    private fun validate(type: String, entries: Map<String, String>, refusals: MutableList<String>) {
        val existing = Gameval.rawEntries(type)
        val owner = HashMap<String, String>(existing.size * 2)
        for ((key, name) in existing) owner[entries[key] ?: name] = key
        for ((key, name) in entries) {
            val taken = owner[name]
            if (taken != null && taken != key) {
                refusals.add("$type $key: '$name' is already the name of $type $taken")
            }
        }
    }

    // ------------------------------------------------------------- catalog i/o

    /**
     * Rewrites the names of [entries] in a catalog, line by line.
     *
     * The catalogs are hand-edited and carry a `source`, a `date` and a
     * `by_channel` breakdown nobody wants reformatted, so this replaces the one
     * string on each entry's line rather than re-serialising the file. Only
     * `coverage.named` and `coverage.placeholder` are recomputed, because naming a
     * previously unnamed id changes them and the build checks that they are true.
     */
    private fun writeCatalog(file: File, entries: Map<String, String>): Int {
        val type = file.name.removeSuffix(".json")
        val lines = file.readText().split("\n").toMutableList()
        val pending = HashMap(entries)
        var inEntries = false
        var named = 0
        var total = 0
        var applied = 0
        val coverage = ArrayList<Int>()
        for (index in lines.indices) {
            val line = lines[index]
            if (!inEntries) {
                // `"type": "varc"` and the header strings look like entries, so
                // nothing is read as one until the `entries` object opens.
                if (line.trimEnd().endsWith("\"entries\": {")) inEntries = true
                else if (NUMBER.matchEntire(line)?.groupValues?.get(2) in COUNTS) coverage.add(index)
                continue
            }
            val match = ENTRY.matchEntire(line) ?: continue
            val key = match.groupValues[2]
            val replacement = pending.remove(key)
            val name = replacement ?: match.groupValues[3]
            total++
            if (name != GamevalCatalog.placeholder(type, key)) named++
            if (replacement == null) continue
            lines[index] = match.groupValues[1] + "\"" + key + "\": \"" + name + "\"" + match.groupValues[4]
            applied++
        }
        if (pending.isNotEmpty()) {
            error("${file.name} has no entry for ${pending.keys.sorted().take(5)}")
        }
        // The build checks these against the entries, and naming a previously
        // unnamed id moves one from `placeholder` to `named`.
        for (index in coverage) {
            val match = NUMBER.matchEntire(lines[index]) ?: continue
            val value = if (match.groupValues[2] == "named") named else total - named
            lines[index] = match.groupValues[1] + "\"" + match.groupValues[2] + "\": " + value + match.groupValues[4]
        }
        file.writeText(lines.joinToString("\n"))
        return applied
    }

    // --------------------------------------------------------------- rewriting

    /** The identifiers a rewrite replaces, keyed by where they can appear. */
    class Renames(
        /** Script and variable names, which are globals. */
        val globals: Map<String, String>,
        /** Namespace -> member -> member, for the flat gameval types. */
        val members: Map<String, Map<String, String>>,
        /** `Component.<interface>` namespace names. */
        val interfaces: Map<String, String>,
        /** Old `Component.<interface>` name -> its members. */
        val components: Map<String, Map<String, String>>,
    ) {
        fun isEmpty(): Boolean =
            globals.isEmpty() && members.isEmpty() && interfaces.isEmpty() && components.isEmpty()

        fun isNotEmpty(): Boolean = !isEmpty()
    }

    /** Turns the bindings that disagree with the catalogs into a rewrite plan. */
    fun renamesFrom(renames: List<Cs2SymbolTable.Rename>): Renames {
        val globals = HashMap<String, String>()
        val members = HashMap<String, HashMap<String, String>>()
        val interfaces = HashMap<String, String>()
        val components = HashMap<String, HashMap<String, String>>()
        val componentNamespace = Cs2Names.NAMESPACES.getValue(Gameval.COMPONENT)
        for (rename in renames) {
            if (!rename.project.contains('.')) {
                globals[rename.project] = rename.catalog
                continue
            }
            val from = rename.project.split('.')
            val to = rename.catalog.split('.')
            if (from.size != to.size) continue
            if (from.first() == componentNamespace && from.size == 3) {
                if (from[1] != to[1]) interfaces[from[1]] = to[1]
                if (from[2] != to[2]) components.getOrPut(from[1]) { HashMap() }[from[2]] = to[2]
                continue
            }
            if (from.size == 2 && from[1] != to[1]) {
                members.getOrPut(from.first()) { HashMap() }[from[1]] = to[1]
            }
        }
        return Renames(globals, members, interfaces, components)
    }

    /**
     * Replaces identifiers in one script, leaving everything else byte for byte.
     *
     * The rewrite runs over the token stream and copies the source between tokens
     * through untouched, so a comment or a string literal that happens to read like
     * a name is never rewritten. Hook format strings in particular have to survive
     * verbatim: they are what several hundred scripts' byte-identity rests on.
     *
     * A name is only replaced in the position it can occur in - a global where no
     * `.` precedes it, a member only after the namespace it belongs to - so a
     * component called `chatbox` and a script called `chatbox` never touch.
     */
    fun rewrite(source: String, renames: Renames): String {
        if (renames.isEmpty()) return source
        val componentNamespace = Cs2Names.NAMESPACES.getValue(Gameval.COMPONENT)
        val namespaces = Cs2Names.NAMESPACES.values.toSet()
        val tokens = try {
            Cs2Lexer(source).tokenise()
        } catch (e: Exception) {
            return source
        }
        val out = StringBuilder(source.length + 64)
        var copied = 0

        fun replace(token: Token, text: String) {
            out.append(source, copied, token.start)
            out.append(text)
            copied = token.start + token.text.length
        }

        fun dot(index: Int): Boolean {
            val token = tokens.getOrNull(index) ?: return false
            return token.kind == TokenKind.PUNCT && token.text == "."
        }

        fun identifier(index: Int): Token? =
            tokens.getOrNull(index)?.takeIf { it.kind == TokenKind.IDENT }

        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            if (token.kind != TokenKind.IDENT || dot(i - 1)) {
                i++
                continue
            }
            if (token.text in namespaces && dot(i + 1)) {
                val first = identifier(i + 2)
                if (first == null) {
                    i++
                    continue
                }
                if (token.text == componentNamespace) {
                    val second = if (dot(i + 3)) identifier(i + 4) else null
                    val group = first.text
                    renames.interfaces[group]?.let { replace(first, it) }
                    if (second != null) {
                        renames.components[group]?.get(second.text)?.let { replace(second, it) }
                        i += 5
                        continue
                    }
                    i += 3
                    continue
                }
                renames.members[token.text]?.get(first.text)?.let { replace(first, it) }
                i += 3
                continue
            }
            renames.globals[token.text]?.let { replace(token, it) }
            i++
        }
        out.append(source, copied, source.length)
        return out.toString()
    }

    private const val OLD_SUFFIX = "Old"

    private val CLIENTSCRIPT_NAME = Regex("\\[([^,\\]]*),([^\\]]*)\\]")

    private val FUNCTION = Regex("""^function (\w+)\s*\(""", RegexOption.MULTILINE)

    private val NUMERIC_HEADER = Regex("""^// clientscript \d+$""", RegexOption.MULTILINE)

    /** The binding line: a bracketed catalog name, or a bare one. */
    private val GAMEVAL_HEADER = Regex("""^// (?:\[[^\]\n]*\]|\w+)$""", RegexOption.MULTILINE)

    /** A triple-slash reference, whose `../` depth follows the file's folder. */
    private val REFERENCE = Regex("""/// <reference path="(?:\.\./)*\./?([\w.]+\.d\.ts)" />""")

    /** What a name may not look like: `<prefix>_<digits>` is some id's placeholder. */
    private val PLACEHOLDER_TAIL = Regex("\\d+(_\\d+)?")

    /** One `"<key>": "<name>"` line of a catalog's `entries`, with its layout kept. */
    private val ENTRY = Regex("""(\s*)"([^"]+)": "([^"]*)"(,?)""")

    /** One `"<name>": <number>` line of a catalog's `coverage`. */
    private val NUMBER = Regex("""(\s*)"(\w+)": (\d+)(,?)""")

    /** The two `coverage` counts that have to stay true as names are added. */
    private val COUNTS = setOf("named", "placeholder")
}
