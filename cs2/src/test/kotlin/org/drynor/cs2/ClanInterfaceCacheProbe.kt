package org.drynor.cs2

import java.io.File
import world.gregs.voidps.cache.FileCache

/** Explicit probe: compile and pack only the clan rosters into a disposable cache copy. */
object ClanInterfaceCacheProbe {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2) { "Expected clientscript source folder and disposable packed cache folder" }
        val compiler = Cs2Compiler.fromSources(File(args[0]))
        val scripts = linkedMapOf(4301 to "proc/clansettings_list_build.ts", 4436 to "cs2/cs2_4436.ts", 4444 to "cs2/cs2_4444.ts")
        for ((id, path) in scripts) {
            val file = File(args[0], path)
            check(compiler.scriptIdOf(file) == id)
            val bytes = compiler.compile(file)
            val script = Cs2Codec.decode(bytes)
            val sort = script.instructions.indexOfFirst { it.op == if (id == 4301) Cs2Op.ACTIVECLANSETTINGS_GETSORTEDAFFINEDSLOT else Cs2Op.ACTIVECLANCHANNEL_GETSORTEDUSERSLOT }
            check(sort > 0)
            check(script.instructions[sort - 1].op == Cs2Op.PUSH_INT_LOCAL)
            check(script.instructions[sort + 1].op == Cs2Op.POP_INT_LOCAL)
            FileCache(args[1], writable = true).use { cache ->
                check(cache.data(12, id) != null)
                cache.write(12, id, 0, bytes)
            }
            FileCache(args[1]).use { cache ->
                val readBack = requireNotNull(cache.data(12, id))
                check(readBack.contentEquals(bytes))
                check(Cs2Codec.decode(readBack).instructions == script.instructions)
            }
            println("Packed script $id readback matches compiled source: load slot -> sorted slot -> store member index")
        }
        println("Only scripts ${scripts.keys} were written; other cache archives were preserved")
    }
}
