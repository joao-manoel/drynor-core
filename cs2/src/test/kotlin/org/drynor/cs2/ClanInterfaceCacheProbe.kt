package org.drynor.cs2

import java.io.File
import world.gregs.voidps.cache.FileCache

/** Explicit probe: compile and pack only the clan roster into a disposable cache copy. */
object ClanInterfaceCacheProbe {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2) { "Expected clientscript source folder and disposable packed cache folder" }
        val compiler = Cs2Compiler.fromSources(File(args[0]))
        val file = File(args[0], "proc/clansettings_list_build.ts")
        check(compiler.scriptIdOf(file) == 4301)
        val bytes = compiler.compile(file)
        val script = Cs2Codec.decode(bytes)
        val sort = script.instructions.indexOfFirst { it.op == Cs2Op.ACTIVECLANSETTINGS_GETSORTEDAFFINEDSLOT }
        check(sort > 0)
        check(script.instructions[sort - 1].op == Cs2Op.PUSH_INT_LOCAL)
        check(script.instructions[sort + 1].op == Cs2Op.POP_INT_LOCAL)
        FileCache(args[1], writable = true).use { cache ->
            check(cache.data(12, 4301) != null)
            cache.write(12, 4301, 0, bytes)
        }
        FileCache(args[1]).use { cache ->
            val readBack = requireNotNull(cache.data(12, 4301))
            check(readBack.contentEquals(bytes))
            check(Cs2Codec.decode(readBack).instructions == script.instructions)
        }
        println("Packed script 4301 readback matches compiled source: load slot -> opcode 956 -> store member index")
        println("Only script 4301 was written; other cache archives were preserved")
    }
}
