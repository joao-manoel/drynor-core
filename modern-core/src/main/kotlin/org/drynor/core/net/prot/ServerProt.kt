package org.drynor.core.net.prot

/**
 * Marker for a server -> client message.
 *
 * Implementations are plain wire messages: they carry the fields the client reads and nothing
 * else. Payload types shared between messages live in `org.drynor.core.net.prot.dto`, the entity
 * update schema lives in `org.drynor.core.net.prot.update`, and the byte layout for a given
 * revision lives under `org.drynor.core.net.prot.revision`.
 */
interface ServerProt
