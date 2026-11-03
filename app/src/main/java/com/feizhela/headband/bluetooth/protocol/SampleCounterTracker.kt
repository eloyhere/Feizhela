package com.feizhela.headband.bluetooth.protocol

/**
 * One counter sequence tracker, intended to be owned by a single recording session.
 *
 * Observe every sample in arrival order, including packet boundaries. This class is not thread safe;
 * the recording owner must serialize access. It makes no clock or packet-index assumptions.
 *
 * Policy: let delta = (current - previous) modulo 65536. Delta 0 is duplicate; delta 1 is
 * continuous, except 65535 -> 0 is wrap; delta 2..32767 is missing delta - 1 samples, even
 * across wrap. Delta >= 32768 is backward or an ambiguous reset, with no inferred drop count.
 * Every valid observation becomes the baseline, including duplicates and ambiguous transitions.
 * Counter values alone cannot prove a device reset or distinguish a large loss from backward data.
 */
class SampleCounterTracker {
    private var previous: Int? = null

    /** Starts a fresh counter sequence; call when beginning a distinct recording session. */
    fun reset() {
        previous = null
    }

    /** Classifies and records the next unsigned firmware sample counter. */
    fun observe(counter: Int): Result {
        require(counter in 0..Sample.MAX_UNSIGNED_16) { "Counter must be an unsigned 16-bit value" }
        val prior = previous
        previous = counter
        if (prior == null) return Result.First(counter)

        val delta = (counter - prior) and Sample.MAX_UNSIGNED_16
        return when {
            delta == 0 -> Result.Duplicate(counter, prior)
            delta == 1 -> if (prior == Sample.MAX_UNSIGNED_16) {
                Result.Wrap(counter, prior)
            } else {
                Result.Continuous(counter, prior)
            }
            delta < HALF_RANGE -> Result.Missing(counter, prior, delta - 1)
            else -> Result.BackwardOrAmbiguousReset(counter, prior, delta)
        }
    }

    sealed interface Result {
        val counter: Int

        data class First(override val counter: Int) : Result
        data class Continuous(override val counter: Int, val previous: Int) : Result
        data class Wrap(override val counter: Int, val previous: Int) : Result
        data class Missing(override val counter: Int, val previous: Int, val droppedCount: Int) : Result
        data class Duplicate(override val counter: Int, val previous: Int) : Result

        /** The counter moved backward or the modulo delta is too large to distinguish from reset. */
        data class BackwardOrAmbiguousReset(
            override val counter: Int,
            val previous: Int,
            val moduloDelta: Int,
        ) : Result
    }

    private companion object {
        const val HALF_RANGE = 0x8000
    }
}
