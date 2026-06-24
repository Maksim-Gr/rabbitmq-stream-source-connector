package com.github.maksimgr

import com.rabbitmq.stream.OffsetSpecification
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class RabbitOffsetResolverTest {
    @Test
    @DisplayName("Should return OffsetSpecification.first for first")
    fun testFirstOffset() {
        val result = RabbitOffsetResolver.resolveOffset("first")
        assertEquals(result, OffsetSpecification.first())
    }

    @Test
    @DisplayName("Should return OffsetSpecification.last for last")
    fun testLastOffset() {
        val result = RabbitOffsetResolver.resolveOffset("last")
        assertEquals(result, OffsetSpecification.last())
    }

    @Test
    @DisplayName("Should return OffsetSpecification.next for next")
    fun testNextOffset() {
        val result = RabbitOffsetResolver.resolveOffset("next")
        assertEquals(result, OffsetSpecification.next())
    }

    @Test
    @DisplayName("Should parse valid timestamp into OffsetSpecification.timestamp")
    fun testValidTimestamp() {
        val input = "01.01.2024 12:00:00"
        val expectedTimestamp =
            LocalDateTime.parse(input, DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()

        val result = RabbitOffsetResolver.resolveOffset(input)
        assertEquals(OffsetSpecification.timestamp(expectedTimestamp), result)
    }

    @Test
    @DisplayName("Timestamp offset must be expressed in milliseconds, not seconds")
    fun testTimestampUnitIsMillis() {
        // 2024-01-01T12:00:00Z == 1704110400 seconds == 1704110400000 milliseconds.
        val expectedMillis = 1_704_110_400_000L
        val result = RabbitOffsetResolver.resolveOffset("01.01.2024 12:00:00")
        assertEquals(OffsetSpecification.timestamp(expectedMillis), result)
    }

    @Test
    @DisplayName("committedOffset returns null when no offset has been stored")
    fun testCommittedOffsetMissing() {
        assertNull(RabbitOffsetResolver.committedOffset(null))
        assertNull(RabbitOffsetResolver.committedOffset(emptyMap()))
    }

    @Test
    @DisplayName("committedOffset reads a stored Long")
    fun testCommittedOffsetLong() {
        assertEquals(42L, RabbitOffsetResolver.committedOffset(mapOf("offset" to 42L)))
    }

    @Test
    @DisplayName("committedOffset coerces an Integer round-tripped through the JSON converter")
    fun testCommittedOffsetInteger() {
        assertEquals(42L, RabbitOffsetResolver.committedOffset(mapOf("offset" to 42)))
    }
}
