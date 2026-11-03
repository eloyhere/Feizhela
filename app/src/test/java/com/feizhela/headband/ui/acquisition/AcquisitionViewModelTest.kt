package com.feizhela.headband.ui.acquisition

import androidx.lifecycle.viewModelScope
import com.feizhela.headband.bluetooth.BleConnectionState
import com.feizhela.headband.bluetooth.BleDevice
import com.feizhela.headband.bluetooth.BleEvent
import com.feizhela.headband.bluetooth.BleWriteType
import com.feizhela.headband.bluetooth.BluetoothRepository
import com.feizhela.headband.bluetooth.BluetoothSessionState
import com.feizhela.headband.bluetooth.RawBlePacket
import com.feizhela.headband.recording.AcquisitionControls
import com.feizhela.headband.recording.RecordingMetrics
import com.feizhela.headband.recording.RecordingPhase
import com.feizhela.headband.recording.RecordingState
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for the acquisition ViewModel state mapping and action forwarding. The recording
 * repository, Bluetooth transport and service binder are replaced by fakes, so no Android
 * framework class or emulator is involved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AcquisitionViewModelTest {
    private val device = BleDevice("AA:BB:CC:DD:EE:FF", "Headband")
    private val otherDevice = BleDevice("11:22:33:44:55:66", "Other headband")
    private val serviceUuid = UUID.fromString("5aee1a8a-08de-11ed-861d-0242ac120002")
    private val characteristicUuid = UUID.fromString("405992d6-0cf2-11ed-861d-0242ac120002")

    private val mainDispatcher = StandardTestDispatcher()
    private val createdViewModels = mutableListOf<AcquisitionViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        createdViewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun idleToRecordingMappingExposesPhaseSessionIdAndPacketCount() = runTest {
        val recordingStates = MutableStateFlow(RecordingState())
        val viewModel = createViewModel(recordingStates = recordingStates)
        observe(viewModel)

        assertEquals(AcquisitionPhase.Idle, viewModel.state.value.phase)
        assertNull(viewModel.state.value.sessionId)
        assertEquals(0L, viewModel.state.value.packetCount)

        recordingStates.value = RecordingState(
            phase = RecordingPhase.Recording,
            sessionId = "session-1",
            packetCount = 128L,
            metrics = RecordingMetrics(gapCount = 3),
        )
        runCurrent()

        val state = viewModel.state.value
        assertEquals(AcquisitionPhase.Recording, state.phase)
        assertEquals("session-1", state.sessionId)
        assertEquals(128L, state.packetCount)
        assertEquals(3, state.gapCount)
    }

    @Test
    fun disconnectionClearsConnectionAndConnectedDeviceAddress() = runTest {
        val bluetooth = FakeBluetoothRepository()
        val viewModel = createViewModel(bluetooth = bluetooth)
        observe(viewModel)

        bluetooth.state.value = BluetoothSessionState(
            connection = BleConnectionState.Connected,
            connectedDevice = device,
        )
        runCurrent()

        assertEquals(BleConnectionState.Connected, viewModel.state.value.connection)
        assertEquals(device.address, viewModel.state.value.connectedDeviceAddress)

        bluetooth.state.value = BluetoothSessionState()
        runCurrent()

        assertEquals(BleConnectionState.Idle, viewModel.state.value.connection)
        assertNull(viewModel.state.value.connectedDeviceAddress)
    }

    @Test
    fun recordingActionsAreForwardedToControlsWithTheirArguments() = runTest {
        val controls = FakeAcquisitionControls()
        val viewModel = createViewModel(controls = controls)
        observe(viewModel)

        viewModel.dispatch(AcquisitionAction.StartRecording(device, serviceUuid, characteristicUuid))

        assertEquals(
            listOf(Triple(device, serviceUuid, characteristicUuid)),
            controls.startCalls,
        )
        assertNull(viewModel.state.value.lastError)

        viewModel.dispatch(AcquisitionAction.StopRecording)
        assertEquals(1, controls.stopCalls)
    }

    @Test
    fun discoveredDevicesAndScanningAreDerivedFromBluetoothState() = runTest {
        val bluetooth = FakeBluetoothRepository()
        val viewModel = createViewModel(bluetooth = bluetooth)
        observe(viewModel)

        assertFalse(viewModel.state.value.scanning)
        assertTrue(viewModel.state.value.devices.isEmpty())

        bluetooth.discoveredDevices.value = listOf(device, otherDevice)
        bluetooth.state.value = BluetoothSessionState(connection = BleConnectionState.Scanning)
        runCurrent()

        assertEquals(listOf(device, otherDevice), viewModel.state.value.devices)
        assertTrue(viewModel.state.value.scanning)

        viewModel.dispatch(AcquisitionAction.Scan(setOf(serviceUuid)))
        assertEquals(listOf(setOf(serviceUuid)), bluetooth.scanRequests)

        viewModel.dispatch(AcquisitionAction.StopScan)
        assertEquals(1, bluetooth.stopScanCalls)
    }

    @Test
    fun failedAndInterruptedRecordingsExposeReasonAsLastError() = runTest {
        val recordingStates = MutableStateFlow(RecordingState())
        val viewModel = createViewModel(recordingStates = recordingStates)
        observe(viewModel)

        recordingStates.value = RecordingState(
            phase = RecordingPhase.Failed,
            sessionId = "session-2",
            reason = "Raw packet writer failed",
        )
        runCurrent()

        assertEquals(AcquisitionPhase.Failed, viewModel.state.value.phase)
        assertEquals("session-2", viewModel.state.value.sessionId)
        assertEquals("Raw packet writer failed", viewModel.state.value.lastError)

        recordingStates.value = RecordingState(
            phase = RecordingPhase.Interrupted,
            sessionId = "session-3",
            reason = "BLE_DISCONNECTED",
        )
        runCurrent()

        assertEquals(AcquisitionPhase.Interrupted, viewModel.state.value.phase)
        assertEquals("BLE_DISCONNECTED", viewModel.state.value.lastError)
    }

    @Test
    fun everyRecordingPhaseMapsToTheSameNamedAcquisitionPhase() = runTest {
        val recordingStates = MutableStateFlow(RecordingState())
        val viewModel = createViewModel(recordingStates = recordingStates)
        observe(viewModel)

        RecordingPhase.entries.forEach { phase ->
            recordingStates.value = RecordingState(phase = phase)
            runCurrent()
            assertEquals(AcquisitionPhase.valueOf(phase.name), viewModel.state.value.phase)
        }
    }

    @Test
    fun failedConnectIsReportedAsLastErrorWithoutThrowing() = runTest {
        val bluetooth = FakeBluetoothRepository().apply {
            connectResult = Result.failure<Unit>(IOException("GATT 133"))
        }
        val viewModel = createViewModel(bluetooth = bluetooth)
        observe(viewModel)

        viewModel.dispatch(AcquisitionAction.Connect(device))
        runCurrent()

        assertEquals(listOf(device), bluetooth.connectCalls)
        assertEquals("GATT 133", viewModel.state.value.lastError)

        viewModel.dispatch(AcquisitionAction.Disconnect)
        runCurrent()

        assertEquals(1, bluetooth.disconnectCalls)
        assertNull(viewModel.state.value.lastError)
    }

    @Test
    fun unboundServiceFailureIsReportedInsteadOfThrown() = runTest {
        val controls = FakeAcquisitionControls().apply {
            failure = IllegalStateException("Acquisition service is not bound")
        }
        val viewModel = createViewModel(controls = controls)
        observe(viewModel)

        viewModel.dispatch(AcquisitionAction.StartRecording(device, serviceUuid, characteristicUuid))
        runCurrent()

        assertEquals("Acquisition service is not bound", viewModel.state.value.lastError)
        assertTrue(controls.startCalls.isEmpty())
    }

    private fun createViewModel(
        controls: FakeAcquisitionControls = FakeAcquisitionControls(),
        bluetooth: FakeBluetoothRepository = FakeBluetoothRepository(),
        recordingStates: MutableStateFlow<RecordingState> = MutableStateFlow(RecordingState()),
    ): AcquisitionViewModel =
        AcquisitionViewModel(controls, bluetooth, recordingStates).also { createdViewModels += it }

    /** Subscribes to the state flow because `stateIn(WhileSubscribed)` only maps while observed. */
    private fun TestScope.observe(viewModel: AcquisitionViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.collect { }
        }
        runCurrent()
    }

    private class FakeAcquisitionControls : AcquisitionControls {
        val startCalls = mutableListOf<Triple<BleDevice, UUID, UUID>>()
        var stopCalls = 0
            private set
        var failure: Throwable? = null

        override fun start(device: BleDevice, serviceUuid: UUID, characteristicUuid: UUID) {
            failure?.let { throw it }
            startCalls += Triple(device, serviceUuid, characteristicUuid)
        }

        override fun stop() {
            failure?.let { throw it }
            stopCalls++
        }
    }

    private class FakeBluetoothRepository : BluetoothRepository {
        override val state = MutableStateFlow(BluetoothSessionState())
        override val discoveredDevices = MutableStateFlow<List<BleDevice>>(emptyList())
        override val rawPackets = MutableSharedFlow<RawBlePacket>()
        override val events = MutableSharedFlow<BleEvent>()

        val scanRequests = mutableListOf<Set<UUID>>()
        val connectCalls = mutableListOf<BleDevice>()
        var stopScanCalls = 0
            private set
        var disconnectCalls = 0
            private set
        var connectResult: Result<Unit> = Result.success(Unit)

        override fun startScan(serviceUuids: Set<UUID>): Result<Unit> {
            scanRequests += serviceUuids
            return Result.success(Unit)
        }

        override fun stopScan() {
            stopScanCalls++
        }

        override fun connect(device: BleDevice): Result<Unit> {
            connectCalls += device
            return connectResult
        }

        override fun disconnect() {
            disconnectCalls++
        }

        override fun subscribe(serviceUuid: UUID, characteristicUuid: UUID): Result<Long> =
            Result.success(1L)

        override fun read(serviceUuid: UUID, characteristicUuid: UUID): Result<Long> =
            Result.success(2L)

        override fun write(
            serviceUuid: UUID,
            characteristicUuid: UUID,
            value: ByteArray,
            writeType: BleWriteType,
        ): Result<Long> = Result.success(3L)

        override fun close() = Unit
    }
}
