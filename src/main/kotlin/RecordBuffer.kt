package com.github.maksimgr

import org.apache.kafka.connect.source.SourceRecord
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Blocking FIFO buffer, full when either [maxRecords] or [maxBytes] is reached; methods follow `BlockingQueue`.
 * Like Kafka's `fetch.max.bytes`, a record larger than [maxBytes] is still accepted when the buffer is empty.
 */
class RecordBuffer(
    val maxRecords: Int,
    val maxBytes: Long,
) {
    private class Entry(
        val record: SourceRecord,
        val sizeBytes: Long,
    )

    private val entries = ArrayDeque<Entry>()
    private var bytes = 0L
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val notFull = lock.newCondition()

    init {
        require(maxRecords >= 1) { "maxRecords must be at least 1" }
        require(maxBytes >= 1) { "maxBytes must be at least 1" }
    }

    val size: Int get() = lock.withLock { entries.size }

    val sizeBytes: Long get() = lock.withLock { bytes }

    fun isEmpty(): Boolean = size == 0

    fun offer(
        record: SourceRecord,
        sizeBytes: Long,
        timeout: Long,
        unit: TimeUnit,
    ): Boolean {
        var nanos = unit.toNanos(timeout)
        lock.lockInterruptibly()
        try {
            while (!hasRoomFor(sizeBytes)) {
                if (nanos <= 0L) return false
                nanos = notFull.awaitNanos(nanos)
            }
            entries.addLast(Entry(record, sizeBytes))
            bytes += sizeBytes
            notEmpty.signal()
            return true
        } finally {
            lock.unlock()
        }
    }

    fun poll(
        timeout: Long,
        unit: TimeUnit,
    ): SourceRecord? {
        var nanos = unit.toNanos(timeout)
        lock.lockInterruptibly()
        try {
            while (entries.isEmpty()) {
                if (nanos <= 0L) return null
                nanos = notEmpty.awaitNanos(nanos)
            }
            val entry = entries.removeFirst()
            bytes -= entry.sizeBytes
            notFull.signalAll()
            return entry.record
        } finally {
            lock.unlock()
        }
    }

    fun drainTo(
        target: MutableCollection<SourceRecord>,
        maxRecords: Int,
    ): Int =
        lock.withLock {
            var moved = 0
            while (moved < maxRecords && entries.isNotEmpty()) {
                val entry = entries.removeFirst()
                bytes -= entry.sizeBytes
                target.add(entry.record)
                moved++
            }
            if (moved > 0) notFull.signalAll()
            moved
        }

    fun clear() =
        lock.withLock {
            entries.clear()
            bytes = 0L
            notFull.signalAll()
        }

    override fun toString(): String = lock.withLock { "${entries.size} / $maxRecords records, $bytes / $maxBytes bytes" }

    private fun hasRoomFor(sizeBytes: Long): Boolean = entries.isEmpty() || (entries.size < maxRecords && bytes + sizeBytes <= maxBytes)
}
