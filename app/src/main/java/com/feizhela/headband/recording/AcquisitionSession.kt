package com.feizhela.headband.recording

import com.feizhela.headband.bluetooth.BleCharacteristic
import com.feizhela.headband.bluetooth.BleDevice
import com.feizhela.headband.bluetooth.BluetoothSessionState
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-local gateway to the acquisition owner ([AcquisitionService]).
 *
 * The service — not the UI — owns the BLE transport, the recording repository and the analysis
 * cursor. This interface exposes only read-only snapshots plus intents; it deliberately has no
 * Android `Context`, no binder type and no database access, so a screen/ViewModel can be tested
 * with a fake.
 */
interface AcquisitionSession {
    /** True while the process is bound to the service; commands fail while it is false. */
    val serviceAvailable: StateFlow<Boolean>

    val bluetoothState: StateFlow<BluetoothSessionState>
    val devices: StateFlow<List<BleDevice>>
    val characteristics: StateFlow<List<BleCharacteristic>>
    val recordingState: StateFlow<RecordingState>
    val analysisState: StateFlow<SignalAnalysisRepository.AnalysisState>

    fun startScan(): Result<Unit>

    fun stopScan()

    fun connect(device: BleDevice): Result<Unit>

    fun disconnect()

    fun startRecording(device: BleDevice): Result<Unit>

    fun stopRecording()
}
