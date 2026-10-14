package org.darkan.core.worldlist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class WorldListTest {
    private fun metadata(number: Int, host: String) =
        WorldMetadata(number, host, 43601, "test", Country.USA, false, true, true, false, false)

    @Test
    fun `pinned local world survives registration and removal`() {
        val list = WorldList(300, localWorldId = 300)
        assertEquals("localhost", assertNotNull(list.get(300)).metadata.ipAddress)

        list.put(World(metadata(300, "someone.elses.host")))
        assertEquals("localhost", assertNotNull(list.get(300)).metadata.ipAddress)

        assertNull(list.remove(300))
        assertEquals("localhost", assertNotNull(list.get(300)).metadata.ipAddress)
    }

    @Test
    fun `other worlds register and unregister normally around the pinned one`() {
        val list = WorldList(300, localWorldId = 300)
        list.put(World(metadata(1, "xmr.darkan.org")))
        assertEquals(listOf(1, 300), list.getWorldArray().map { it.metadata.number })
        assertNotNull(list.remove(1))
        assertEquals(listOf(300), list.getWorldArray().map { it.metadata.number })
    }

    @Test
    fun `no local world when unset`() {
        val list = WorldList(300, localWorldId = 0)
        assertNull(list.get(300))
        assertNull(list.getDefault())
    }

    @Test
    fun `a disconnected world stays listed as offline until the ttl passes`() {
        val list = WorldList(300, localWorldId = 0, offlineTtl = 12.hours)
        list.put(World(metadata(1, "xmr.darkan.org")))
        val revision = list.revision

        val world = assertNotNull(list.markOffline(1))
        assertTrue(world.offline)
        assertEquals(-1, list.snapshot().entries.single().playersOnline)
        assertEquals(revision + 1, list.revision)
        assertTrue(list.expireOffline().isEmpty())

        world.offlineSince -= 12.hours.inWholeMilliseconds
        assertEquals(listOf(1), list.expireOffline().map { it.metadata.number })
        assertNull(list.get(1))
    }

    @Test
    fun `a world that reconnects replaces its offline entry`() {
        val list = WorldList(300, localWorldId = 0)
        list.put(World(metadata(1, "xmr.darkan.org")))
        list.markOffline(1)
        list.put(World(metadata(1, "xmr.darkan.org")))
        assertEquals(0, list.snapshot().entries.single().playersOnline)
        assertTrue(list.expireOffline().isEmpty())
    }

    @Test
    fun `the pinned local world is never marked offline`() {
        val list = WorldList(300, localWorldId = 300)
        assertNull(list.markOffline(300))
        assertEquals(0, list.snapshot().entries.single().playersOnline)
    }

    @Test
    fun `snapshot carries the custom cache flag`() {
        val list = WorldList(300, localWorldId = 0)
        list.put(World(metadata(1, "live.darkan.org")))
        list.put(World(metadata(2, "custom.darkan.org")).apply { customCache = true })
        val flags = list.snapshot().entries.associate { it.number to it.customCache }
        assertEquals(mapOf(1 to false, 2 to true), flags)
    }

    @Test
    fun `snapshot range includes a registered world above the configured minimum`() {
        val list = WorldList(300, localWorldId = 0)
        list.put(World(metadata(999, "community.darkan.org")))

        assertEquals(999, list.snapshot().maxWorlds)
    }
}
