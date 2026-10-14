package org.darkan.core.net.prot.revision.rev727

import org.darkan.core.net.prot.ProtSize
import kotlin.test.Test
import kotlin.test.assertTrue

class Rev727BidirectionalCoverageTest {
    private val codec = register727()

    private val ProtSize.empty get() = this is ProtSize.Fixed && length == 0

    @Test
    fun `every server prot a server can encode a client can decode`() {
        val missing = codec.serverProts.values.groupBy { it.opcode }.values
            .filter { shared -> shared.none { it.decoder != null || it.size.empty } }
            .flatMap { shared -> shared.mapNotNull { it.protClass.simpleName } }.sorted()
        assertTrue(missing.isEmpty(), "No ServerProt decoder for: $missing")
    }

    @Test
    fun `a shared server opcode decodes as the prot that owns its decoder`() {
        val unowned = codec.serverProtsByOpcode.values.filter { it.decoder == null && !it.size.empty }
            .filter { indexed -> codec.serverProts.values.any { it.opcode == indexed.opcode && it.decoder != null } }
            .mapNotNull { it.protClass.simpleName }
        assertTrue(unowned.isEmpty(), "Opcode indexed to a prot without a decoder: $unowned")
    }

    @Test
    fun `every client prot a server can decode a client can encode`() {
        val missing = codec.clientProts.values.filter { it.encoder == null && !it.size.empty }.mapNotNull { it.protClass.simpleName }.sorted()
        assertTrue(missing.isEmpty(), "No ClientProt encoder for: $missing")
    }
}
