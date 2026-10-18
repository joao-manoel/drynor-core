package org.drynor.core.model

enum class MoveType(val id: Int) {
    WALK(1),
    RUN(2),
    CRAWL(0),
    TELEPORT(127),
}
