package dev.jarvis.core

/** Every voice entry point must use the same owner check. */
enum class ActivationSource { WAKE_WORD, OVERLAY, ASSISTANT_GESTURE, QUICK_SETTINGS, IN_APP }
