package com.tibatech.gptmax.core.agent

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

/**
 * Fix #6 (code review): per-tool latency metrics so agent performance is
 * measured instead of guessed. Records count, average, max and a
 * nearest-rank p95 from a bounded rolling sample per tool.
 *
 * Pure JVM: unit-testable on the JVM without Android.
 */
class PerformanceMonitor(private val sampleSize: Int = 256) {

    data class ToolStat(
        val tool: String,
        val runs: Long,
        val avgMs: Double,
        val p95Ms: Long,
        val maxMs: Long,
    )

    private class Metric {
        val runs = AtomicLong()
        val totalMs = AtomicLong()
        val maxMs = AtomicLong()
        @Volatile var samples: List<Long> = emptyList()
    }

    private val metrics = ConcurrentHashMap<String, Metric>()
    private val sampleLock = Any()

    fun record(tool: String, durationMs: Long) {
        val metric = metrics.getOrPut(tool) { Metric() }
        metric.runs.incrementAndGet()
        metric.totalMs.addAndGet(durationMs)
        metric.maxMs.updateAndGet { prev -> if (durationMs > prev) durationMs else prev }
        synchronized(sampleLock) {
            val next = metric.samples + durationMs
            metric.samples = if (next.size > sampleSize) next.drop(next.size - sampleSize) else next
        }
    }

    fun snapshot(): List<ToolStat> =
        metrics.entries.map { (tool, metric) ->
            val runs = metric.runs.get()
            val sorted = synchronized(sampleLock) { metric.samples }.sorted()
            val p95 = if (sorted.isEmpty()) {
                0L
            } else {
                val rank = ceil(0.95 * sorted.size).toInt().coerceIn(1, sorted.size)
                sorted[rank - 1]
            }
            ToolStat(
                tool = tool,
                runs = runs,
                avgMs = if (runs == 0L) 0.0 else metric.totalMs.get().toDouble() / runs,
                p95Ms = p95,
                maxMs = metric.maxMs.get(),
            )
        }.sortedBy { it.tool }

    fun reset() {
        metrics.clear()
    }
}
