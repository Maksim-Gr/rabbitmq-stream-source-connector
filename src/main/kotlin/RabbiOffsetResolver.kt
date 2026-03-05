import com.rabbitmq.stream.OffsetSpecification
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object RabbiOffsetResolver {
    val logger: Logger = LoggerFactory.getLogger(RabbitSourceConnector::class.java)
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

    private fun String.toTimestampOrNull(): Long? =
        runCatching {
            LocalDateTime
                .parse(this, formatter)
                .toEpochSecond(ZoneOffset.UTC)
        }.onFailure {
            logger.debug("Failed to parse timestamp offset: '$this'", it)
        }.getOrNull()
}
