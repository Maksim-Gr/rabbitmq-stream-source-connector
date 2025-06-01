import com.rabbitmq.stream.OffsetSpecification
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class RabbiOffsetResolverTest {
    @Test
    @DisplayName("Should return OffsetSpecification.first for first")
    fun testFirstOffset() {
        val result = RabbiOffsetResolver.resolveOffset("first")
        assertEquals(result, OffsetSpecification.first())
    }

    @Test
    @DisplayName("Should return OffsetSpecification.last for last")
    fun testLastOffset() {
        val result = RabbiOffsetResolver.resolveOffset("last")
        assertEquals(result, OffsetSpecification.last())
    }


    @Test
    @DisplayName("Should return OffsetSpecification.next for next")
    fun testNextOffset() {
        val result = RabbiOffsetResolver.resolveOffset("next")
        assertEquals(result, OffsetSpecification.next())
    }


    @Test
    @DisplayName("Should parse valid timestamp into OffsetSpecification.timestamp")
    fun testValidTimestamp() {
        val input = "01.01.2024 12:00:00"
        val expectedTimestamp = LocalDateTime.parse(input, DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))
            .toEpochSecond(ZoneOffset.UTC)

        val result = RabbiOffsetResolver.resolveOffset(input)
        assertEquals(OffsetSpecification.timestamp(expectedTimestamp), result)
    }
}