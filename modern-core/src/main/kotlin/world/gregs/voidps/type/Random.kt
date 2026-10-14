package world.gregs.voidps.type

import java.security.SecureRandom
import kotlin.random.Random

var secureRandom: SecureRandom = SecureRandom()
    private set

var random: Random = Random
    private set

fun setRandom(rand: Random) {
    random = rand
}