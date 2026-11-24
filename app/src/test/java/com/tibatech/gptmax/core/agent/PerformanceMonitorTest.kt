package com.tibatech.gptmax.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fix #6 regression tests: per-tool latency metrics (count, average,
 * nearest-rank p95, max) from a bounded rolling sample.
 */
class PerformanceMonitorTest {

    @Test
    fun `records count average max and p95`() {
        val monitor = PerformanceMonitor(sampleSize = 100)
        listOf(10L, 20L, 30L, 40L, 50L).forEach { monitor.record("read_file", it) }
        val stat = monitor.snapshot().single()
        assertEquals("read_file", stat.tool)
        assertEquals(5L, stat.runs)
        assertEquals(30.0, stat.avgMs, 0.001)
        assertEquals(50L, stat.maxMs)
        // nearest-rank p95 of [10..50] is the 5th value
        assertEquals(50L, stat.p95Ms)
    }

    @Test
    fun `rolling sample keeps recent runs visible in p95`() {
        val monitor = PerformanceMonitor(sampleSize = 256)
        repeat(250) { monitor.record("grep", 1L) }
        repeat(20) { monitor.record("grep", 500L) }
        val stat = monitor.snapshot().single()
        assertEquals(270L, stat.runs)
        assertEquals(500L, stat.maxMs)
        // the 20 recent slow runs occupy >5% of the 256-sample window
        assertEquals(500L, stat.p95Ms)
    }

    @Test
    fun `snapshot is sorted by tool name`() {
        val monitor = PerformanceMonitor()
        monitor.record("write_file", 5L)
        monitor.record("grep", 1L)
        monitor.record("read_file", 2L)
        assertEquals(listOf("grep", "read_file", "write_file"), monitor.snapshot().map { it.tool })
    }

    @Test
    fun `reset clears all metrics`() {
        val monitor = PerformanceMonitor()
        monitor.record("grep", 1L)
        monitor.reset()
        assertTrue(monitor.snapshot().isEmpty())
    }
}
