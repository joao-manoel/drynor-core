package org.darkan.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PasswordPolicyTest {
    @Test
    fun `a password with letters and digits of the minimum length has no problem`() {
        assertNull("abcdefg1".passwordProblem())
        assertNull("longerpassword123".passwordProblem())
    }

    @Test
    fun `too short is reported before anything else`() {
        assertEquals(PasswordProblem.TOO_SHORT, "testes".passwordProblem())
        assertEquals(PasswordProblem.TOO_SHORT, "a1!".passwordProblem())
        assertEquals(PasswordProblem.TOO_SHORT, "".passwordProblem())
    }

    @Test
    fun `symbols and spaces are not alphanumeric`() {
        assertEquals(PasswordProblem.NOT_ALPHANUMERIC, "abcdefg1!".passwordProblem())
        assertEquals(PasswordProblem.NOT_ALPHANUMERIC, "abcd efg1".passwordProblem())
    }

    @Test
    fun `only letters or only digits is too easy`() {
        assertEquals(PasswordProblem.TOO_EASY, "abcdefgh".passwordProblem())
        assertEquals(PasswordProblem.TOO_EASY, "12345678".passwordProblem())
    }
}
