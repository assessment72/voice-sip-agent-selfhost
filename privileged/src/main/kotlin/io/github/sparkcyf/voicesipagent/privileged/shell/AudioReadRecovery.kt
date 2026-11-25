package io.github.sparkcyf.voicesipagent.privileged.shell

/** Classifies AudioRecord.read results without treating transient empty reads as call failure. */
internal class AudioReadRecovery {
    enum class Decision { DATA, RETRY_EMPTY, STOP, STOPPED }

    var consecutiveEmptyReads: Int = 0
        private set

    fun classify(bytesRead: Int, running: Boolean): Decision {
        if (!running) return Decision.STOPPED
        return when {
            bytesRead > 0 -> {
                consecutiveEmptyReads = 0
                Decision.DATA
            }
            bytesRead == 0 -> {
                consecutiveEmptyReads++
                Decision.RETRY_EMPTY
            }
            else -> Decision.STOP
        }
    }
}
