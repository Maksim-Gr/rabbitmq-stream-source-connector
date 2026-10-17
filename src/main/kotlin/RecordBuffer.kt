package com.github.maksimgr

import org.apache.kafka.connect.source.SourceRecord
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Blocking FIFO buffer bounded by both record count and total size in bytes, whichever is reached first.
 *
 * Like Kafka's `fetch.max.bytes`, the byte limit is not absolute: a record larger than [maxBytes] is still
 * accepted when the buffer is empty, so a single oversized message cannot stall consumption.
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

    /** Number of buffered records. */
    val size: Int get() = lock.withLock { entries.size }

    /** Total size in bytes of the buffered records, as reported to [offer]. */
    val sizeBytes: Long get() = lock.withLock { bytes }

    fun isEmpty(): Boolean = size == 0

    /**
     * Appends [record], waiting up to [timeout] for room. Returns false if there was still no room.
     *
     * @throws InterruptedException if the calling thread is interrupted
     */
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

    /**
     * Removes the oldest record, waiting up to [timeout] for one to arrive. Returns null on timeout.
     *
     * @throws InterruptedException if the calling thread is interrupted
     */
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

    /** Moves up to [maxRecords] buffered records into [target] without waiting; returns how many were moved. */
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

    /** Current depth against both limits, e.g. "1234 / 10000 records, 5242880 / 67108864 bytes". */
    override fun toString(): String = lock.withLock { "${entries.size} / $maxRecords records, $bytes / $maxBytes bytes" }

    private fun hasRoomFor(sizeBytes: Long): Boolean = entries.isEmpty() || (entries.size < maxRecords && bytes + sizeBytes <= maxBytes)
}
