package dev.jarvis.core

import java.text.Normalizer
import java.util.Locale

sealed interface LocalCommand {
    data class Torch(val on: Boolean) : LocalCommand
    data class Volume(val change: Int) : LocalCommand
    data class Timer(val seconds: Int) : LocalCommand
    data class Alarm(val hour: Int, val minute: Int) : LocalCommand
    data class OpenApp(val name: String) : LocalCommand
    data object Battery : LocalCommand
    data object Time : LocalCommand
    data object Settings : LocalCommand
    data object Camera : LocalCommand
    data object Help : LocalCommand
    data object Blocked : LocalCommand
    data object Unknown : LocalCommand
}

object CommandRouter {
    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{M}\\p{N}\\s]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    fun route(input: String): LocalCommand {
        if (input.length > 1000) return LocalCommand.Unknown
        val text = normalize(input).replace(Regex("^(?:hey )?jarvis\\s*"), "")
            .replace(Regex("^(?:please|ജാർവിസ്)\\s*"), "").trim()
        if (text.isEmpty()) return LocalCommand.Unknown
        // Preserve ':' before normalization, which otherwise turns 7:30 into two words.
        val alarmText = Normalizer.normalize(input, Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim()
            .removePrefix("jarvis,").removePrefix("jarvis").trim().removeSuffix(".").removeSuffix("!")
        val alarm = Regex("(?:please )?(?:set |create )?(?:a |an |my )?alarm (?:at|for) (\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?(?: please)?").matchEntire(alarmText)
        if (alarm != null) {
            val hour = alarm.groupValues[1].toInt()
            val minute = alarm.groupValues[2].ifBlank { "0" }.toInt()
            val period = alarm.groupValues[3]
            if (minute !in 0..59 || (period.isNotEmpty() && hour !in 1..12) || hour !in 0..23) return LocalCommand.Unknown
            // Bare 1..12 is ambiguous: let the model ask for AM/PM.
            if (period.isEmpty() && hour in 1..12) return LocalCommand.Unknown
            return LocalCommand.Alarm(if (period.isEmpty()) hour else hour % 12 + if (period == "pm") 12 else 0, minute)
        }
        if (Regex("\\b(pay|payment|transfer|delete|send|call|password|otp|shutdown|reboot)\\b").containsMatchIn(text) ||
            listOf("പണം", "പണമട", "ഡിലീറ്റ്", "അയക്ക", "വിളിക്ക", "പാസ്‌വേഡ്", "ഒടിപി").any(text::contains)) return LocalCommand.Blocked
        if (text in setOf("help", "what can you do", "commands", "സഹായം")) return LocalCommand.Help
        if (text in setOf("time", "what time is it", "what is the time", "what s the time", "സമയം", "സമയം പറയൂ")) return LocalCommand.Time
        if (text in setOf("battery", "battery status", "how much battery is left", "battery level", "ബാറ്ററി", "ബാറ്ററി എത്ര")) return LocalCommand.Battery
        if (text in setOf("settings", "open settings", "സെറ്റിംഗ്സ്", "സെറ്റിംഗ്സ് തുറക്കൂ")) return LocalCommand.Settings
        if (text in setOf("camera", "open camera", "ക്യാമറ", "ക്യാമറ തുറക്കൂ")) return LocalCommand.Camera
        if (text in setOf("volume up", "turn up the volume", "increase volume", "ശബ്ദം കൂട്ടൂ", "വോളിയം കൂട്ടൂ")) return LocalCommand.Volume(1)
        if (text in setOf("volume down", "turn down the volume", "decrease volume", "ശബ്ദം കുറയ്ക്കൂ", "വോളിയം കുറയ്ക്കൂ")) return LocalCommand.Volume(-1)
        val light = "(?:the )?(?:flashlight|flash light|torch|light)"
        if (Regex("(?:turn|switch|put) on $light|(?:turn|switch|put) $light on|$light on").matches(text) || text in setOf("ടോർച്ച് ഓൺ", "ടോർച്ച് ഓൺ ചെയ്യൂ", "ലൈറ്റ് ഓൺ ചെയ്യൂ")) return LocalCommand.Torch(true)
        if (Regex("(?:turn|switch|put) off $light|(?:turn|switch|put) $light off|$light off").matches(text) || text in setOf("ടോർച്ച് ഓഫ്", "ടോർച്ച് ഓഫ് ചെയ്യൂ", "ലൈറ്റ് ഓഫ് ചെയ്യൂ")) return LocalCommand.Torch(false)
        val timer = Regex("(?:set |start )?(?:a |an )?(?:(\\d{1,4}) (second|seconds|minute|minutes|hour|hours) timer|timer (?:for )?(\\d{1,4}) (second|seconds|minute|minutes|hour|hours))").matchEntire(text)
        if (timer != null) {
            val number = (timer.groupValues[1].ifEmpty { timer.groupValues[3] }).toInt()
            val unit = timer.groupValues[2].ifEmpty { timer.groupValues[4] }
            val seconds = number.toLong() * when { unit.startsWith("hour") -> 3600; unit.startsWith("minute") -> 60; else -> 1 }
            return if (seconds in 1..86_400) LocalCommand.Timer(seconds.toInt()) else LocalCommand.Unknown
        }
        val words = mapOf("one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "ten" to "10", "fifteen" to "15", "thirty" to "30")
        if (text.contains("timer")) {
            val replaced = text.split(' ').joinToString(" ") { words[it] ?: it }
            if (replaced != text) return route(replaced)
        }
        if (text.startsWith("open ") && text.length > 5) return LocalCommand.OpenApp(text.removePrefix("open "))
        if (text.endsWith(" തുറക്കൂ")) return LocalCommand.OpenApp(text.removeSuffix(" തുറക്കൂ").trim())
        return LocalCommand.Unknown
    }
}
