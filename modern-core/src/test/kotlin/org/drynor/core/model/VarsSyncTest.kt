package org.drynor.core.model

import kotlin.test.*

class VarsSyncTest {
    @Test fun `changes during packet delivery survive for the next tick`() {
        val vars = Vars()
        var delivered = 0
        vars.setPacketSink({
            delivered++
            if (delivered == 1) vars.setVar(3, 30)
        }, 32)
        vars.setVar(1, 10); vars.setVar(2, 20)
        vars.syncVarsToClient()
        assertEquals(2, delivered)
        vars.syncVarsToClient()
        assertEquals(3, delivered)
        assertEquals(30, vars.getVar(3))
        vars.syncVarsToClient()
        assertEquals(3, delivered)
    }

    @Test fun `failed delivery retries pending values without losing newer state`() {
        val vars = Vars()
        var fail = true
        var delivered = 0
        vars.setPacketSink({
            if (fail) { vars.setVar(1, 11); throw IllegalStateException("writer unavailable") }
            delivered++
        }, 32)
        vars.setVar(1, 10); vars.setVar(2, 20)
        assertFailsWith<IllegalStateException> { vars.syncVarsToClient() }
        fail = false
        vars.syncVarsToClient()
        assertEquals(2, delivered)
        assertEquals(11, vars.getVar(1))
    }
}
