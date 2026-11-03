package com.feizhela.headband.storage

/**
 * Per-session archive totals. Room projects one row per session so the list/detail mapping needs
 * a bounded number of queries instead of paging through raw packet payloads.
 */
data class SessionPacketTotals(
    val sessionId: String,
    val packetCount: Long,
)

/**
 * Gap totals of one session. [closedGapMillis] sums only finished gaps; an unfinished gap is
 * reported through [earliestOpenStartMillis] so the caller can measure it against the session end
 * (or now) without this SQL layer inventing an end time.
 */
data class SessionGapTotals(
    val sessionId: String,
    val gapCount: Int,
    val closedGapMillis: Long?,
    val earliestOpenStartMillis: Long?,
)

/**
 * Epoch totals of one session: [receivedSamples]/[expectedSamples] are the coverage source of
 * truth once analysis has persisted at least one epoch; [analyzedEpochCount] counts epochs with a
 * stored spectral estimate.
 */
data class SessionEpochTotals(
    val sessionId: String,
    val epochCount: Int,
    val receivedSamples: Long,
    val expectedSamples: Long,
    val analyzedEpochCount: Int,
)
