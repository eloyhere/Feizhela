package com.feizhela.headband.ui.acquisition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.feizhela.headband.bluetooth.BleConnectionState
import com.feizhela.headband.bluetooth.BluetoothRepository
import com.feizhela.headband.recording.AcquisitionControls
import com.feizhela.headband.recording.RecordingPhase
import com.feizhela.headband.recording.RecordingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Turns the observable acquisition sources into the single [AcquisitionUiState] consumed by the
 * screen and forwards [AcquisitionAction]s to the repositories / bound service.
 *
 * Boundaries: this class never parses packets, never touches the database, never builds CSV and
 * holds no Android [android.content.Context]. All collaborators are injected so the mapping and
 * action forwarding can be tested on the JVM.
 */
class AcquisitionViewModel(
    private val controls: AcquisitionControls,
    private val bluetooth: BluetoothRepository,
    recordingStates: StateFlow<RecordingState>,
) : ViewModel() {
    /**
     * Latest failed user action. Cleared before each dispatch so a stale error cannot survive a
     * later attempt; a recording failure from [RecordingState.reason] is reported by [state].
     */
    private val actionError = MutableStateFlow<String?>(null)

    val state: StateFlow<AcquisitionUiState> = combine(
        bluetooth.state,
        bluetooth.discoveredDevices,
        recordingStates,
        actionError,
    ) { session, devices, recording, error ->
        AcquisitionUiState(
            phase = recording.phase.toAcquisitionPhase(),
            sessionId = recording.sessionId,
            packetCount = recording.packetCount,
            gapCount = recording.metrics.gapCount,
            connection = session.connection,
            connectedDeviceAddress = session.connectedDevice?.address,
            devices = devices,
            scanning = session.connection == BleConnectionState.Scanning,
            lastError = if (recording.phase.failedOrInterrupted()) {
                recording.reason ?: error
            } else {
                error
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AcquisitionUiState(),
    )

    /**
     * Handles one user intent. Repository and binder failures are captured as the next
     * [AcquisitionUiState.lastError]; they never escape to the UI as exceptions.
     */
    fun dispatch(action: AcquisitionAction) {
        when (action) {
            is AcquisitionAction.Scan -> attemptResult { bluetooth.startScan(action.serviceUuids) }
            AcquisitionAction.StopScan -> attempt { bluetooth.stopScan() }
            is AcquisitionAction.Connect -> attemptResult { bluetooth.connect(action.device) }
            AcquisitionAction.Disconnect -> attempt { bluetooth.disconnect() }
            is AcquisitionAction.StartRecording -> attempt {
                controls.start(action.device, action.serviceUuid, action.characteristicUuid)
            }
            AcquisitionAction.StopRecording -> attempt { controls.stop() }
        }
    }

    private fun attemptResult(block: () -> Result<Unit>) {
        actionError.value = null
        try {
            block().onFailure { actionError.value = messageOf(it) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            actionError.value = messageOf(error)
        }
    }

    private fun attempt(block: () -> Unit) {
        actionError.value = null
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            actionError.value = messageOf(error)
        }
    }

    private fun messageOf(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName

    private fun RecordingPhase.toAcquisitionPhase(): AcquisitionPhase = when (this) {
        RecordingPhase.Idle -> AcquisitionPhase.Idle
        RecordingPhase.Starting -> AcquisitionPhase.Starting
        RecordingPhase.Recording -> AcquisitionPhase.Recording
        RecordingPhase.Stopping -> AcquisitionPhase.Stopping
        RecordingPhase.Completed -> AcquisitionPhase.Completed
        RecordingPhase.Interrupted -> AcquisitionPhase.Interrupted
        RecordingPhase.Failed -> AcquisitionPhase.Failed
    }

    private fun RecordingPhase.failedOrInterrupted(): Boolean =
        this == RecordingPhase.Failed || this == RecordingPhase.Interrupted
}
