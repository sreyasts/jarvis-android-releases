package dev.jarvis.core

/** The bundled lexicon expects words; never silently drop battery/time numbers. */
object SpokenText {
    private val small = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    fun prepare(text: String): String {
        val clock = Regex("\\b(\\d{1,2}):(\\d{2})\\s*([AP]M)\\b", RegexOption.IGNORE_CASE)
        val times = text.replace(clock) { match ->
            val hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].toInt()
            val part = when { minute == 0 -> ""; minute < 10 -> " oh ${number(minute)}"; else -> " ${number(minute)}" }
            "${number(hour)}$part ${match.groupValues[3][0].lowercaseChar()} m"
        }
        return times.replace(Regex("\\d+(?:\\.\\d+)?")) { match ->
            val parts = match.value.split('.')
            val whole = parts[0].toIntOrNull()?.takeIf { it in 0..999_999 }?.let(::number)
                ?: parts[0].map { small[it.digitToInt()] }.joinToString(" ")
            if (parts.size == 1) whole else "$whole point ${parts[1].map { small[it.digitToInt()] }.joinToString(" ")}"
        }
    }
    private fun number(value: Int): String = when {
        value < 20 -> small[value]
        value < 100 -> tens[value / 10] + if (value % 10 != 0) " ${small[value % 10]}" else ""
        value < 1000 -> "${small[value / 100]} hundred" + if (value % 100 != 0) " and ${number(value % 100)}" else ""
        else -> "${number(value / 1000)} thousand" + if (value % 1000 != 0) " ${number(value % 1000)}" else ""
    }
}
