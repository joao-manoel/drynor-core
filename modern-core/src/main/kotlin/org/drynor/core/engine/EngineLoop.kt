package org.drynor.core.engine

import kotlinx.coroutines.*
import org.drynor.core.EnvVars
import org.drynor.core.Logger.logErrorToDatabase
import java.util.concurrent.TimeUnit
import kotlin.properties.Delegates

class EngineLoop(val steps: Array<Runnable>, val reportTickConcern: (Long, Map<String, Long>) -> Unit) {
    var startCycle by Delegates.notNull<Long>()
    var engineCycle by Delegates.notNull<Long>()
    val uptimeTicks get() = engineCycle - startCycle
    var totalMillis = 0L
    val averageMillis get() = totalMillis / uptimeTicks
    var lowestMillis = 50000L
    var highestMillis = 0L

    fun start(scope: CoroutineScope) = scope.launch {
        var start: Long
        var time: Long
        val stepTimes = mutableMapOf<String, Long>()
        try {
            startCycle = System.currentTimeMillis() / EnvVars.worldCycleMillis
            engineCycle = startCycle
            stepTimes.clear()
            while (isActive) {
                start = System.nanoTime()
                for (step in steps) {
                    val stageStart = System.nanoTime()
                    step.run()
                    stepTimes.put(step::class::simpleName.get() ?: "Unknown", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stageStart))
                }
                time = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                if (time > EnvVars.worldCycleWarnMillis)
                    reportTickConcern(time, stepTimes)
                delay(EnvVars.worldCycleMillis - time)
                if (time > highestMillis)
                    highestMillis = time
                if (time < lowestMillis)
                    lowestMillis = time
                engineCycle++
                totalMillis += time
            }
        } catch (e: Exception) {
            logErrorToDatabase("Severe lobby engine loop error", e)
        }
    }
}