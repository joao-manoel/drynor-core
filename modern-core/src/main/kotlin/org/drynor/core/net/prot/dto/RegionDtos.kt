/**
 * Payload types carried by the region building packets.
 */
package org.drynor.core.net.prot.dto

enum class InstanceLoadType(val index: Int) {
    // not sure if zero is even a valid input to the packet glancing at the
    // client logic, it shouldn't really perform different than 1 as they both
    // set the loading type to the same value
    STANDARD(1),                   //No difference from non-dynamic logic essentially
    STANDARD_NO_ENTITY_RESET(2),   //Same as above except this one won't reset local entities on load (Matrix and most other bases default to this)
    LARGE(3),                      //Ignores render-distance restrictions on zone updates (current Void default)
    LARGE_NO_UPDATEZONE_RESET(4)   //Ignores render-distance restrictions on zone updates and doesn't reset objs, locs, etc on load
}
