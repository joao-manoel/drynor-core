package org.darkan.core.random

import java.security.SecureRandom
import kotlin.math.sqrt

private val RANDOM = SecureRandom()

object Random {
    fun random(max: Int): Int = random(0, max)
    fun random(min: Int, max: Int): Int = min + RANDOM.nextInt(max - min)
    fun random(max: Long): Long = random(0L, max)
    fun random(min: Long, max: Long): Long = min + RANDOM.nextLong(max - min)
    fun randomInclusive(max: Int): Int = randomInclusive(0, max)
    fun randomInclusive(min: Int, max: Int): Int = random(min, max + 1)
    fun randomD(max: Double): Double = randomD(0.0, max)
    fun randomD(min: Double, max: Double): Double = min + (max - min) * RANDOM.nextDouble()
    fun randomD(): Double = RANDOM.nextDouble()
    fun gaussian(mean: Int, variance: Int) = (RANDOM.nextGaussian() * sqrt(variance.toDouble()) + mean).toInt()
}