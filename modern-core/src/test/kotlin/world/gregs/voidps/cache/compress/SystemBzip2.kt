package world.gregs.voidps.cache.compress

import java.io.File

/** The system `bzip2 -1 -c` over a pipe, the reference [BZip2Encoder] is held byte-identical to. */
object SystemBzip2 {
    const val BINARY = "/usr/bin/bzip2"

    val available: Boolean get() = File(BINARY).canExecute()

    fun compress(data: ByteArray): ByteArray {
        val process = ProcessBuilder(BINARY, "-1", "-c").start()
        val writer = Thread { process.outputStream.use { it.write(data) } }
        writer.start()
        val bytes = process.inputStream.use { it.readBytes() }
        writer.join()
        val status = process.waitFor()
        check(status == 0) { "$BINARY exited with $status" }
        return bytes
    }
}
