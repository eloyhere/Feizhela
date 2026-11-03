package com.feizhela.headband.recording

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.feizhela.headband.bluetooth.BleCharacteristic
import com.feizhela.headband.bluetooth.BleDevice
import com.feizhela.headband.bluetooth.BluetoothSessionState
import java.util.UUID
import com.feizhela.headband.bluetooth.protocol.HeadbandProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Legacy command surface kept for the existing [com.feizhela.headband.ui.acquisition.AcquisitionViewModel]
 * and its tests. New UI code should use [AcquisitionSession] instead.
 */
interface AcquisitionControls {
    fun start(device: BleDevice, serviceUuid: UUID, characteristicUuid: UUID)

    fun stop()
}

/**
 * Binds the UI layer to [AcquisitionService] and mirrors the binder's read-only flows into
 * process-lifetime [StateFlow]s, so screens keep observing stable sources while the binding is
 * being established, re-established or lost.
 *
 * Command methods return [Result] failures instead of throwing for expected states (service not
 * bound, BLE scan rejected). They are expected on the main thread, like the platform callbacks.
 */
class AcquisitionServiceConnection(
    context: Context,
    private val scope: CoroutineScope,
) : ServiceConnection, AcquisitionSession {
    private val appContext = context.applicationContext
    private val mutableBinder = MutableStateFlow<AcquisitionService.LocalBinder?>(null)

    /** Non-null only while the service is actually bound; callers must not assume it is set. */
    val binder: StateFlow<AcquisitionService.LocalBinder?> = mutableBinder.asStateFlow()

    private val mutableAvailable = MutableStateFlow(false)
    private val mutableBluetooth = MutableStateFlow(BluetoothSessionState())
    private val mutableDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    private val mutableCharacteristics = MutableStateFlow<List<BleCharacteristic>>(emptyList())
    private val mutableRecording = MutableStateFlow(RecordingState())
    private val mutableAnalysis = MutableStateFlow(SignalAnalysisRepository.AnalysisState())

    override val serviceAvailable: StateFlow<Boolean> = mutableAvailable.asStateFlow()
    override val bluetoothState: StateFlow<BluetoothSessionState> = mutableBluetooth.asStateFlow()
    override val devices: StateFlow<List<BleDevice>> = mutableDevices.asStateFlow()
    override val characteristics: StateFlow<List<BleCharacteristic>> =
        mutableCharacteristics.asStateFlow()
    override val recordingState: StateFlow<RecordingState> = mutableRecording.asStateFlow()
    override val analysisState: StateFlow<SignalAnalysisRepository.AnalysisState> =
        mutableAnalysis.asStateFlow()

    private var bindRequested = false
    private var mirrorJobs: List<Job> = emptyList()

    init {
        scope.launch {
            mutableBinder.collectLatest { binder ->
                mirrorJobs.forEach(Job::cancel)
                mirrorJobs = emptyList()
                if (binder == null) {
                    mutableAvailable.value = false
                    mutableBluetooth.value = BluetoothSessionState()
                    mutableDevices.value = emptyList()
                    mutableCharacteristics.value = emptyList()
                    mutableRecording.value = RecordingState()
                    mutableAnalysis.value = SignalAnalysisRepository.AnalysisState()
                } else {
                    mutableAvailable.value = true
                    mirrorJobs = listOf(
                        scope.launch { binder.bluetoothState.collect { mutableBluetooth.value = it } },
                        scope.launch { binder.discoveredDevices.collect { mutableDevices.value = it } },
                        scope.launch { binder.discoveredCharacteristics.collect { mutableCharacteristics.value = it } },
                        scope.launch { binder.state.collect { mutableRecording.value = it } },
                        scope.launch { binder.analysisState.collect { mutableAnalysis.value = it } },
                    )
                }
            }
        }
    }

    /** Idempotent: repeated calls do not create additional bindings. */
    fun bind() {
        if (bindRequested) return
        bindRequested = true
        val intent = Intent(appContext, AcquisitionService::class.java)
        val bound = runCatching {
            appContext.bindService(intent, this, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) {
            // The system refused the binding; do not pretend a binder is on its way.
            bindRequested = false
            mutableBinder.value = null
            mutableAvailable.value = false
        }
    }

    /** Idempotent: safe to call when never bound, while connecting, or more than once. */
    fun unbind() {
        if (!bindRequested) return
        bindRequested = false
        mutableBinder.value = null
        mutableAvailable.value = false
        runCatching { appContext.unbindService(this) }
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        // A null or foreign binder is not usable; keep the flow truthful instead of assuming.
        mutableBinder.value = service as? AcquisitionService.LocalBinder
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        // The binding itself is still alive, so Android may reconnect; only the binder is gone.
        mutableBinder.value = null
    }

    override fun onBindingDied(name: ComponentName?) {
        // The binding is dead and cannot be reused: release it and leave re-binding to the caller.
        bindRequested = false
        mutableBinder.value = null
        mutableAvailable.value = false
        runCatching { appContext.unbindService(this) }
    }

    override fun onNullBinding(name: ComponentName?) {
        // The service returned a null binder; the platform requires an explicit unbind.
        mutableBinder.value = null
        mutableAvailable.value = false
        unbind()
    }

    override fun startScan(): Result<Unit> = withBinder { it.startScan() }

    override fun stopScan() {
        mutableBinder.value?.stopScan()
    }

    override fun connect(device: BleDevice): Result<Unit> = withBinder { it.connect(device) }

    override fun disconnect() {
        mutableBinder.value?.disconnect()
    }

    override fun startRecording(device: BleDevice): Result<Unit> = withBinder { binder ->
        // The service owns foreground eligibility, connection and subscription; failures during
        // the asynchronous start surface through recordingState.
        binder.startRecording(
            device,
            HeadbandProtocol.SERVICE_UUID,
            HeadbandProtocol.DATA_CHARACTERISTIC_UUID,
        )
        Result.success(Unit)
    }

    override fun stopRecording() {
        mutableBinder.value?.stopRecording()
    }

    private fun <T> withBinder(block: (AcquisitionService.LocalBinder) -> Result<T>): Result<T> {
        val local = mutableBinder.value
            ?: return Result.failure(IllegalStateException("Acquisition service is not bound"))
        return try {
            block(local)
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    companion object {
        /** Establishes the foreground-service lifetime before any BLE connection is attempted. */
        fun startForeground(context: Context) = AcquisitionService.startForeground(context)
    }
}
