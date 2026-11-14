package dev.jarvis.app

data class JarvisProfile(val name: String = "", val priorities: String = "") {
    companion object {
        const val MAX_NAME = 40
        const val MAX_PRIORITIES = 400
    }
}

internal fun jarvisGreeting(language: JarvisLanguage, name: String): String =
    when (language) {
        JarvisLanguage.MALAYALAM -> if (name.isBlank()) "ഞാൻ ജാർവിസ്. എങ്ങനെ സഹായിക്കാം?" else "$name, എങ്ങനെ സഹായിക്കാം?"
        JarvisLanguage.ENGLISH -> if (name.isBlank()) "I'm here. How can I help?" else "I'm here, $name. How can I help?"
    }

/** Both assistant entry points use this process-local context from the main thread. */
internal object RecentConversation {
    private val turns = ArrayDeque<Pair<String, String>>()

    fun snapshot(): List<Pair<String, String>> = turns.toList()

    fun remember(request: String, reply: String) {
        turns.addLast(request.take(300) to reply.take(500))
        if (turns.size > 2) turns.removeFirst()
    }

    fun clear() = turns.clear()
}
