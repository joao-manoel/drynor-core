/**
 * Payload types carried by the account packets.
 */
package org.darkan.core.net.prot.dto

/**
 * Reply codes for `CreateAccountReply` (opcode 87), as the client's `cs2_3220` resolves them.
 *
 * The script is the source of truth: it switches on the raw code to pick the error text, so a
 * code outside this table renders as "Unexpected server response.".
 */
enum class CreateAccountReplyOpcode(val value: Int) {
    SUCCESS(2),
    SERVER_ERROR(3),
    SERVER_BUSY(7),
    CREATION_UNAVAILABLE(9),
    EMAIL_ALREADY_IN_USE(20),
    INVALID_EMAIL(21),
    PASSWORD_LENGTH(30),
    PASSWORD_NOT_ALPHANUMERIC(31),
    PASSWORD_TOO_EASY(33),
    INVALID_PASSWORD(34),
    CLIENT_OUTDATED(37),
    SERVER_UNAVAILABLE(38)
}

/** Reply codes for `CreateCheckEmailReply` (opcode 1), as the client's `cs2_2284` resolves them. */
enum class CheckEmailReplyOpcode(val value: Int) {
    VALID(2),
    SERVER_ERROR(3),
    EMAIL_ALREADY_IN_USE(20),
    INVALID_EMAIL(21)
}
