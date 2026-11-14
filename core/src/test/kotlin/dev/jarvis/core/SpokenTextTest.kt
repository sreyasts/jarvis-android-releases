package dev.jarvis.core
import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenTextTest {
    @Test fun `preserves numbers in battery and time replies`() {
        assertEquals("Your battery is at one hundred percent.", SpokenText.prepare("Your battery is at 100 percent."))
        assertEquals("It's four oh five p m.", SpokenText.prepare("It's 4:05 PM."))
        assertEquals("one point two", SpokenText.prepare("1.2"))
        assertEquals("one thousand four hundred and forty minutes", SpokenText.prepare("1440 minutes"))
    }
}
