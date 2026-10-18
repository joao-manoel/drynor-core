package org.drynor.core.net.prot

/**
 * Marker for a client -> server message.
 *
 * Implementations are plain wire messages, decoded by the revision codec and dispatched to a
 * `PacketHandler`. The byte layout for a given revision lives under
 * `org.drynor.core.net.prot.revision`.
 */
interface ClientProt
