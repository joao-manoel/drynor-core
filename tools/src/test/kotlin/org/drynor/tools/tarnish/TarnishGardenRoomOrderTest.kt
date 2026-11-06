package org.drynor.tools.tarnish

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TarnishGardenRoomOrderTest {
    @Test fun insertsAfterOublietteAndPreservesStableComponentIdsAndOtherRowOrder() {
        val rows=listOf(
            TarnishGardenRoomOrder.Row(100,"Throne room",0),
            TarnishGardenRoomOrder.Row(101,"Oubliette",64),
            TarnishGardenRoomOrder.Row(102,"Dungeon - Pit",128),
            TarnishGardenRoomOrder.Row(103,"Treasure room",192),
            TarnishGardenRoomOrder.Row(118,"Superior garden",256),
        )
        val result=TarnishGardenRoomOrder.positions(rows)
        assertEquals(listOf(100,101,118,102,103),result.entries.sortedBy { it.value }.map { it.key })
        assertEquals(rows.map { it.component }.toSet(),result.keys)
        assertEquals(rows.map { it.y }.toSet(),result.values.toSet())
    }

    @Test fun refusesMissingAnchorAndAmbiguousCoordinates() {
        val garden=TarnishGardenRoomOrder.Row(118,"Superior garden",128)
        assertFailsWith<NoSuchElementException> {
            TarnishGardenRoomOrder.positions(listOf(TarnishGardenRoomOrder.Row(1,"Throne room",0),TarnishGardenRoomOrder.Row(2,"Other",64),garden))
        }
        assertFailsWith<IllegalArgumentException> {
            TarnishGardenRoomOrder.positions(listOf(TarnishGardenRoomOrder.Row(1,"Oubliette",0),TarnishGardenRoomOrder.Row(2,"Other",0),garden))
        }
    }
}
