package com.github.maksimgr

import com.rabbitmq.stream.OffsetSpecification
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object RabbitOffsetResolver {
    val logger: Logger = LoggerFactory.getLogger(RabbitOffsetResolver::class.java)
    private val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")

    fun resolveOffset(offsetStr: String): OffsetSpecification =
        when (val normalized = offsetStr.trim().lowercase()) {
            "first" -> OffsetSpecification.first()
            "last" -> OffsetSpecification.last()
            "next" -> OffsetSpecification.next()
            else -> {
                val timestamp =
                    normalized.toTimestampOrNull()
                        ?: throw IllegalArgumentException(
                            "Invalid offset '$offsetStr'. Expected 'first', 'last', 'next' or timestamp format 'dd.MM.yyyy HH:mm:ss'.",
                        )

                OffsetSpecification.timestamp(timestamp)
            }
        }

    /**
     * Coerces the source offset read back from Kafka Connect's offset store into a Long.
     *
     * Connect serialises source offsets through a JSON converter, so a value committed as a
     * Long can round-trip back as an Integer (Jackson narrows numeric types to the smallest
     * that fits). Matching only on `Long` would miss the committed offset and silently restart
     * the stream from the configured start offset. Accepting any [Number] keeps resume robust
     * regardless of the converter in use.
     */
    fun committedOffset(storedOffset: Map<String, Any?>?): Long? = (storedOffset?.get("offset") as? Number)?.toLong()

    private fun String.toTimestampOrNull(): Long? =
        runCatching {
            // OffsetSpecification.timestamp() expects milliseconds since the epoch.
            LocalDateTime
                .parse(this, formatter)
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        }.onFailure {
            logger.debug("Failed to parse timestamp offset: '$this'", it)
        }.getOrNull()
}
