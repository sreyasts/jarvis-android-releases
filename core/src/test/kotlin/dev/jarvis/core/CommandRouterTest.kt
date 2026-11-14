package dev.jarvis.core

import org.junit.Assert.*
import org.junit.Test

class CommandRouterTest {
    @Test fun `alarms accept imperfect grammar and preserve minute and period`() {
        assertEquals(LocalCommand.Alarm(7, 0), CommandRouter.route("set a alarm at 7am"))
        assertEquals(LocalCommand.Alarm(19, 30), CommandRouter.route("Please set an alarm for 7:30 pm."))
        assertEquals(LocalCommand.Alarm(0, 5), CommandRouter.route("set an alarm at 12:05am"))
        assertEquals(LocalCommand.Alarm(23, 10), CommandRouter.route("alarm for 23:10"))
        listOf("alarm for 7", "alarm for 25:00", "alarm for 7:99pm").forEach {
            assertEquals(LocalCommand.Unknown, CommandRouter.route(it))
        }
    }
    @Test fun `routes supported English and Malayalam commands`() {
        assertEquals(LocalCommand.Torch(true), CommandRouter.route("Jarvis, turn on the flashlight!"))
        assertEquals(LocalCommand.Torch(false), CommandRouter.route("ടോർച്ച് ഓഫ് ചെയ്യൂ"))
        assertEquals(LocalCommand.Volume(1), CommandRouter.route("ശബ്ദം കൂട്ടൂ"))
        assertEquals(LocalCommand.Timer(300), CommandRouter.route("Set a five minute timer."))
        assertEquals(LocalCommand.Timer(90), CommandRouter.route("timer for 90 seconds"))
        assertEquals(LocalCommand.OpenApp("whatsapp"), CommandRouter.route("Open WhatsApp"))
    }
    @Test fun `never guesses on unsupported or sensitive actions`() {
        listOf("send a message", "pay five dollars", "delete my photos", "transfer money", "call Bob")
            .forEach { assertEquals(LocalCommand.Blocked, CommandRouter.route(it)) }
        assertEquals(LocalCommand.Unknown, CommandRouter.route("set a 9999 hour timer"))
        assertEquals(LocalCommand.Unknown, CommandRouter.route("turn on the thing"))
        assertEquals(LocalCommand.Unknown, CommandRouter.route(""))
    }
}
