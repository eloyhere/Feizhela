package com.feizhela.headband.simulation

/** Default wall-clock length of one simulated recording; callers may override it. */
const val SIMULATION_DEFAULT_DURATION_MILLIS: Long = 3_000L

/**
 * A named, fully deterministic acquisition scenario.
 *
 * [transport] configures the fake firmware and every transport fault; [storageWriteDelayMillis]
 * is a test-harness-only knob that delays each Room batch insert so the slow-storage scenario
 * exercises backpressure without touching the production storage code.
 */
data class SimulationScenario(
    val name: String,
    val description: String,
    val transport: SimulatedTransportConfig = SimulatedTransportConfig(),
    val durationMillis: Long = SIMULATION_DEFAULT_DURATION_MILLIS,
    val storageWriteDelayMillis: Long = 0L,
) {
    init {
        require(name.isNotBlank()) { "Scenario name must not be blank" }
        require(durationMillis > 0L) { "durationMillis must be positive" }
        require(storageWriteDelayMillis >= 0L) { "storageWriteDelayMillis must be >= 0" }
    }

    /** Creates a fresh transport; each run starts from sample counter zero and the same seed. */
    fun createClient(): SimulatedBluetoothClient = SimulatedBluetoothClient(transport)
}

/**
 * The scenario catalogue used by [SimulationRun] and the MuMu instrumentation manual runner.
 *
 * Healthy runs are the baseline; the other entries inject one specific failure so the recording
 * chain can be observed without a physical headband.
 */
object SimulationScenarios {
    /** Baseline: 104 samples/s, no faults, clean stop. */
    fun healthy(durationMillis: Long = SIMULATION_DEFAULT_DURATION_MILLIS): SimulationScenario =
        SimulationScenario(
            name = "healthy",
            description = "Continuous simulated firmware stream with no injected faults",
            transport = SimulatedTransportConfig(),
            durationMillis = durationMillis,
        )

    /** Every 13th packet disappears in the air; device counters keep advancing across the hole. */
    fun droppedPackets(durationMillis: Long = SIMULATION_DEFAULT_DURATION_MILLIS): SimulationScenario =
        SimulationScenario(
            name = "droppedPackets",
            description = "Notification loss every 13 packets; transport sees a counter gap",
            transport = SimulatedTransportConfig(
                firmware = FirmwareSimulatorConfig(dropEveryNPackets = 13),
            ),
            durationMillis = durationMillis,
        )

    /** Link loss after ~1.2 s of streaming; the session must become INTERRUPTED, not COMPLETED. */
    fun disconnectMidSession(durationMillis: Long = SIMULATION_DEFAULT_DURATION_MILLIS): SimulationScenario =
        SimulationScenario(
            name = "disconnectMidSession",
            description = "GATT link is lost after 1.2 s of notifications",
            transport = SimulatedTransportConfig(forceDisconnectAfterMillis = 1_200L),
            durationMillis = durationMillis,
        )

    /** High notification rate plus a delayed DAO insert, to exercise write backlog telemetry. */
    fun slowStorage(durationMillis: Long = SIMULATION_DEFAULT_DURATION_MILLIS): SimulationScenario =
        SimulationScenario(
            name = "slowStorage",
            description = "4x notification rate with a 350 ms delay per Room batch insert",
            transport = SimulatedTransportConfig(packetIntervalMillis = 24L),
            durationMillis = durationMillis,
            storageWriteDelayMillis = 350L,
        )

    /** Counter starts near 0xFFFF so the 16-bit sample counter wraps within the first packets. */
    fun counterWrap(durationMillis: Long = SIMULATION_DEFAULT_DURATION_MILLIS): SimulationScenario =
        SimulationScenario(
            name = "counterWrap",
            description = "Sample counter starts 16 values below the uint16 wrap point",
            transport = SimulatedTransportConfig(
                firmware = FirmwareSimulatorConfig(counterStart = 0xFFFF - 15),
                packetIntervalMillis = 32L,
            ),
            durationMillis = durationMillis,
        )

    /** CCCD write is rejected: no notification may be faked and the session must end FAILED. */
    fun subscriptionFailure(durationMillis: Long = 1_500L): SimulationScenario =
        SimulationScenario(
            name = "subscriptionFailure",
            description = "CCCD subscription is rejected with GATT status 133",
            transport = SimulatedTransportConfig(subscriptionFailure = true),
            durationMillis = durationMillis,
        )

    /** Link stays connected after 1 s but stops producing notifications (stuck firmware). */
    fun stalledLink(durationMillis: Long = SIMULATION_DEFAULT_DURATION_MILLIS): SimulationScenario =
        SimulationScenario(
            name = "stalledLink",
            description = "Notifications stop after 1 s while the GATT session stays connected",
            transport = SimulatedTransportConfig(
                firmware = FirmwareSimulatorConfig(stallAfterMillis = 1_000L),
            ),
            durationMillis = durationMillis,
        )

    /** Catalogue in a stable order; each entry uses that scenario's default duration. */
    val all: List<SimulationScenario> = listOf(
        healthy(),
        droppedPackets(),
        disconnectMidSession(),
        slowStorage(),
        counterWrap(),
        subscriptionFailure(),
        stalledLink(),
    )

    /** Case-insensitive lookup for the instrumentation `-e scenario=<name>` switch. */
    fun byName(name: String): SimulationScenario? =
        all.firstOrNull { it.name.equals(name, ignoreCase = true) }
}
