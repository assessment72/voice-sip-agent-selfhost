package io.github.sparkcyf.voicesipagent.voice

/** Select the exact configured cellular account; never fall back to another SIM or a VoIP provider. */
fun <T> selectSimAccount(
    selectedKey: String,
    candidates: List<T>,
    keyOf: (T) -> String,
    isSim: (T) -> Boolean,
): T? {
    if (selectedKey.isBlank()) return null
    return candidates.firstOrNull { keyOf(it) == selectedKey && isSim(it) }
}
