package com.feizhela.headband.recording

/**
 * Write-backlog, batch-write latency and acquisition-gap telemetry for one recording session.
 *
 * The acquisition path must not publish state per packet, so every counter here is sampled when
 * a batch is flushed (plus once at session start and once at session end). All durations are
 * milliseconds.
 */
data class RecordingMetrics(
    /** Packets accepted into the writer queue but not yet durably inserted. */
    val queuedPackets: Int = 0,
    /** High-water mark of [queuedPackets] observed while this session was recording. */
    val maxQueuedPackets: Int = 0,
    /** Packets successfully inserted into Room during this session. */
    val writtenPackets: Long = 0,
    /**
     * Wait of the oldest packet in the most recently flushed batch, measured from the moment it
     * entered the writer queue until the batch insert started.
     */
    val oldestQueuedAgeMillis: Long = 0,
    /** Duration of the most recent successful batch insert. */
    val lastBatchWriteMillis: Long = 0,
    /** Slowest successful batch insert observed in this session. */
    val maxBatchWriteMillis: Long = 0,
    /**
     * Size of the recording database in bytes. Currently always 0: the repository is constructed
     * with a DAO only and has no database path, so the size is not fabricated. A future
     * construction site can supply a size provider without changing this contract.
     */
    val databaseBytes: Long = 0,
    /** Number of durable acquisition gaps recorded for this session. */
    val gapCount: Int = 0,
    /** Sum of known dropped-packet estimates attached to this session's gaps. */
    val droppedEstimate: Long = 0,
)
