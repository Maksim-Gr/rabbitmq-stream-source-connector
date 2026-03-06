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
                .toEpochSecond(ZoneOffset.UTC)

        val result = RabbitOffsetResolver.resolveOffset(input)
        assertEquals(OffsetSpecification.timestamp(expectedTimestamp), result)
    }
}
