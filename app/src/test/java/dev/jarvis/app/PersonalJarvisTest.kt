package dev.jarvis.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalJarvisTest {
    @Test fun greetingUsesChosenNameAndLanguage() {
        assertEquals("I'm here, Sreya. How can I help?", jarvisGreeting(JarvisLanguage.ENGLISH, "Sreya"))
        assertEquals("I'm here. How can I help?", jarvisGreeting(JarvisLanguage.ENGLISH, ""))
        assertEquals("Sreya, എങ്ങനെ സഹായിക്കാം?", jarvisGreeting(JarvisLanguage.MALAYALAM, "Sreya"))
    }

    @Test fun conversationKeepsTwoBoundedTurnsAndClears() {
        RecentConversation.clear()
        try {
            RecentConversation.remember("Oldest", "Old reply")
            RecentConversation.remember("x".repeat(350), "y".repeat(550))
            RecentConversation.remember("Recent", "Got it")
            assertEquals(listOf("x".repeat(300) to "y".repeat(500), "Recent" to "Got it"), RecentConversation.snapshot())
        } finally {
            RecentConversation.clear()
        }
        assertTrue(RecentConversation.snapshot().isEmpty())
    }

    @Test fun chargingAdviceRequiresAConfirmedLowUnpluggedBattery() {
        assertTrue(batteryAdvice(15, 0).first.contains("Plug it in"))
        for ((level, plugged) in listOf(16 to 0, 15 to 1, 10 to null, 10 to -1, -1 to 0)) {
            assertEquals("" to "", batteryAdvice(level, plugged))
        }
    }
}
