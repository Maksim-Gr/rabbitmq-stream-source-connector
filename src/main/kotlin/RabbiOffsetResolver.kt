import com.rabbitmq.stream.OffsetSpecification
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object RabbiOffsetResolver {
    private val timeStampPattern = Regex("""\d{2}\.\d{2}\.\d{4} \d{2}:\d{2}:\d{2}""")
    private val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")

    fun resolveOffset(offsetStr: String): OffsetSpecification {
        return when (offsetStr.lowercase()) {
            "first" -> OffsetSpecification.first()
            "last" -> OffsetSpecification.last()
            "next" -> OffsetSpecification.next()
            else -> {
                if (timeStampPattern.matches(offsetStr)) {
                    try {
                        val dateTime = LocalDateTime.parse(offsetStr, formatter)
                        val timestamp = dateTime.toEpochSecond(ZoneOffset.UTC)
                        OffsetSpecification.timestamp(timestamp)
                    } catch (e: Exception) {
                        OffsetSpecification.first()
                    }
                } else {
                    OffsetSpecification.first()
                }
            }
        }
    }

}