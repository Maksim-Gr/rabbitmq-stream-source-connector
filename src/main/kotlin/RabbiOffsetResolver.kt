import com.rabbitmq.stream.OffsetSpecification
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object RabbiOffsetResolver {
    private val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")

    fun resolveOffset(offsetStr: String): OffsetSpecification =
        when (offsetStr.lowercase()) {
            "first" -> OffsetSpecification.first()
            "last" -> OffsetSpecification.last()
            "next" -> OffsetSpecification.next()
            else ->
                offsetStr.toTimestampOrNull()?.let {
                    OffsetSpecification.timestamp(it)
                } ?: OffsetSpecification.first()
        }

    private fun String.toTimestampOrNull(): Long? =
        runCatching {
            LocalDateTime
                .parse(this, formatter)
                .toEpochSecond(ZoneOffset.UTC)
        }.getOrNull()
}
