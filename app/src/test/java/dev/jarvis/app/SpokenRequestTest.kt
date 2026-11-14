package dev.jarvis.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenRequestTest {
    @Test fun keepsTheFullRequestAfterTheAddress() {
        assertEquals("How can I do this?", spokenRequest("Hey Jarvis, How can I do this?"))
        assertEquals("what time is it?", spokenRequest("Jarvis—what time is it?"))
        assertEquals("സമയം എത്രയായി?", spokenRequest("ഹേ ജാർവിസ്‌, സമയം എത്രയായി?"))
    }

    @Test fun treatsAnAddressAloneAsAGreeting() {
        assertEquals("", spokenRequest("Hey Jarvis!"))
        assertEquals("", spokenRequest("ജാർവിസ്"))
    }

    @Test fun preservesMentionsAndLongerWords() {
        assertEquals("Who is Jarvis?", spokenRequest("Who is Jarvis?"))
        assertEquals("Jarvisville", spokenRequest("Jarvisville"))
    }
}
