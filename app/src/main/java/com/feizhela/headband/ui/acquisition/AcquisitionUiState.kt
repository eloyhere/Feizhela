package com.feizhela.headband.ui.acquisition

import com.feizhela.headband.bluetooth.BleConnectionState
import com.feizhela.headband.bluetooth.BleDevice
import java.util.UUID

/**
 * One immutable snapshot of the acquisition screen.
 *
 * Connection, scanning, recording and failure are expressed as values of [phase] and
 * [connection] (plus the derived [scanning]) instead of several independent booleans, so the UI
 * cannot render mutually contradictory combinations such as "connected" while [phase] is
 * [AcquisitionPhase.Idle].
 */
data class AcquisitionUiState(
    val phase: AcquisitionPhase = AcquisitionPhase.Idle,
    val sessionId: String? = null,
    val packetCount: Long = 0,
    val gapCount: Int = 0,
    val connection: BleConnectionState = BleConnectionState.Idle,
    val connectedDeviceAddress: String? = null,
    val devices: List<BleDevice> = emptyList(),
    val scanning: Boolean = false,
    val lastError: String? = null,
)

/**
 * Recording lifecycle as the screen needs it. This is a deliberate copy of
 * [com.feizhela.headband.recording.RecordingPhase] so the UI package does not expose the
 * repository model; the mapping in
 * [AcquisitionViewModel] is one-to-one and exhaustive.
 */
enum class AcquisitionPhase { Idle, Starting, Recording, Stopping, Completed, Interrupted, Failed }

/**
 * Every intent the acquisition screen may send. Screens must not call repositories, the
 * recording controller or the bound service directly.
 */
sealed interface AcquisitionAction {
    data class Scan(val serviceUuids: Set<UUID> = emptySet()) : AcquisitionAction

    data object StopScan : AcquisitionAction

    data class Connect(val device: BleDevice) : AcquisitionAction

    data object Disconnect : AcquisitionAction

    data class StartRecording(
        val device: BleDevice,
        val serviceUuid: UUID,
        val characteristicUuid: UUID,
    ) : AcquisitionAction

    data object StopRecording : AcquisitionAction
}
