package org.darkan.core.net.prot

/**
 * Marker for a server -> client message.
 *
 * Implementations are plain wire messages: they carry the fields the client reads and nothing
 * else. Payload types shared between messages live in `org.darkan.core.net.prot.dto`, the entity
 * update schema lives in `org.darkan.core.net.prot.update`, and the byte layout for a given
 * revision lives under `org.darkan.core.net.prot.revision`.
 */
interface ServerProt
