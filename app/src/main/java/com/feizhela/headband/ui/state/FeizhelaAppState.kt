package com.feizhela.headband.ui.state

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.feizhela.headband.bluetooth.BleConnectionState
import com.feizhela.headband.bluetooth.BleDevice
import com.feizhela.headband.bluetooth.BluetoothSessionState
import com.feizhela.headband.recording.AcquisitionServiceConnection
import com.feizhela.headband.recording.AcquisitionSession
import com.feizhela.headband.recording.RecordingPhase
import com.feizhela.headband.recording.RecordingState
import com.feizhela.headband.storage.AcquisitionGapEntity
import com.feizhela.headband.storage.RecordingDatabase
import com.feizhela.headband.storage.RecordingExporter
import com.feizhela.headband.storage.RecordingLibrary
import com.feizhela.headband.storage.RecordingSessionEntity
import com.feizhela.headband.storage.SignalEpochEntity
import com.feizhela.headband.storage.SessionGapTotals
import com.feizhela.headband.storage.SessionEpochTotals
import com.feizhela.headband.ui.ThemeMode
import com.feizhela.headband.ui.model.*
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** UI adapter over the service-owned BLE/acquisition state and the durable Room archive. */
class FeizhelaAppState internal constructor(
    context: Context,
    private val scope: CoroutineScope,
) {
    var themeMode by mutableStateOf(ThemeMode.System)
    var dynamicColor by mutableStateOf(false)
    var device by mutableStateOf(DeviceUiState(isDemo = false))
        private set
    var tonight by mutableStateOf(TonightUiState(isDemo = false))
        private set
    var sessions by mutableStateOf<List<SessionSummary>>(emptyList())
        private set
    var diary by mutableStateOf<DiaryEntry?>(DiaryStore(context).read())
        private set

    val acquisition: AcquisitionSession = AcquisitionServiceConnection(context, scope)
    private val connection = acquisition as AcquisitionServiceConnection
    private val database = RecordingDatabase.open(context)
    private val library = RecordingLibrary(database.recordingDao, database.signalEpochDao)
    private val exporter = RecordingExporter(database.recordingDao, database.signalEpochDao)
    private val diaryStore = DiaryStore(context)
    private val gapsById = mutableStateMapOf<String, List<AcquisitionGapEntity>>()
    private val epochsById = mutableStateMapOf<String, List<SignalEpochEntity>>()
    private val detailJobs = mutableMapOf<String, Job>()
    private var entities: List<RecordingSessionEntity> = emptyList()
    private var packetTotals = emptyMap<String, Long>()
    private var gapTotals = emptyMap<String, SessionGapTotals>()
    private var epochTotals = emptyMap<String, SessionEpochTotals>()

    init {
        connection.bind()
        scope.launch {
            combine(acquisition.bluetoothState, acquisition.devices, acquisition.recordingState) { bt, devices, recording ->
                Triple(bt, devices, recording)
            }.collect { (bt, devices, recording) -> updateLiveState(bt, devices, recording) }
        }
        scope.launch(Dispatchers.IO) {
            library.observeSessions().collect { rows ->
                entities = rows
                refreshSummaries()
            }
        }
        scope.launch(Dispatchers.IO) {
            library.observePacketTotals().collect { rows -> packetTotals = rows.associate { it.sessionId to it.packetCount }; refreshSummaries() }
        }
        scope.launch(Dispatchers.IO) {
            library.observeGapTotals().collect { rows -> gapTotals = rows.associateBy { it.sessionId }; refreshSummaries() }
        }
        scope.launch(Dispatchers.IO) {
            library.observeEpochTotals().collect { rows -> epochTotals = rows.associateBy { it.sessionId }; refreshSummaries() }
        }
    }

    private fun refreshSummaries() {
        val now = System.currentTimeMillis()
        sessions = entities.map { row ->
            val packets = packetTotals[row.id]
            val epochs = epochTotals[row.id]
            val gap = gapTotals[row.id]
            val span = row.endedAtMillis?.let { (it - row.startedAtMillis).coerceAtLeast(0) }
            val gapMillis = gap?.closedGapMillis?.plus(gap.earliestOpenStartMillis?.let { ((row.endedAtMillis ?: now) - it).coerceAtLeast(0) } ?: 0)
            val coverage = epochs?.takeIf { it.expectedSamples > 0 }?.let { (100f * it.receivedSamples / it.expectedSamples).coerceIn(0f, 100f) }
            SessionSummary(row.id, row.startedAtMillis, row.endedAtMillis, row.deviceName, row.deviceAddress,
                row.status.toSessionStatus(), SessionMetrics(span, epochs?.receivedSamples, packets, coverage,
                    null, gapMillis), gap?.gapCount ?: 0, isDemo = false)
        }
        val active = entities.firstOrNull { it.status == "ACTIVE" }
        if (active == null && tonight.phase == TonightPhase.Recording) tonight = TonightUiState(connection = device.connection, isDemo = false)
    }

    private fun updateLiveState(bt: BluetoothSessionState, devices: List<BleDevice>, recording: RecordingState) {
        val status = when (bt.connection) {
            BleConnectionState.Scanning -> ConnectionUiStatus.Scanning
            BleConnectionState.Connecting -> ConnectionUiStatus.Connecting
            BleConnectionState.Connected -> ConnectionUiStatus.Connected
            BleConnectionState.Closed -> ConnectionUiStatus.Unavailable
            else -> if (bt.lastError != null) ConnectionUiStatus.Error else ConnectionUiStatus.Disconnected
        }
        val conn = ConnectionUiState(status, bt.connectedDevice?.name, bt.connectedDevice?.address,
            lastError = bt.lastError?.message)
        device = DeviceUiState(connection = conn, scanning = status == ConnectionUiStatus.Scanning,
            devices = devices.map { DeviceUi(it.address, it.name, it.rssi, isDemo = false) },
            servicesDiscovered = bt.servicesDiscovered, lastError = bt.lastError?.message, isDemo = false)
        val phase = when (recording.phase) {
            RecordingPhase.Starting -> TonightPhase.Starting
            RecordingPhase.Recording -> TonightPhase.Recording
            RecordingPhase.Stopping -> TonightPhase.Stopping
            RecordingPhase.Completed -> TonightPhase.Completed
            RecordingPhase.Interrupted -> TonightPhase.Interrupted
            RecordingPhase.Failed -> TonightPhase.Failed
            RecordingPhase.Idle -> TonightPhase.Idle
        }
        val row = recording.sessionId?.let { id -> entities.firstOrNull { it.id == id } }
        val span = row?.let { ( (it.endedAtMillis ?: System.currentTimeMillis()) - it.startedAtMillis).coerceAtLeast(0) } ?: 0L
        tonight = TonightUiState(phase = phase, connection = conn, startedAtMillis = row?.startedAtMillis,
            recordedSpanMillis = span, packetCount = recording.packetCount,
            sampleCount = recording.sessionId?.let { epochTotals[it]?.receivedSamples },
            coveragePercent = recording.sessionId?.let { epochTotals[it] }?.let { totals -> if (totals.expectedSamples > 0) 100f * totals.receivedSamples / totals.expectedSamples else null },
            candidateWindowCount = 0,
            gapCount = recording.metrics.gapCount, quality = QualitySummary(),
            technicalDetail = recording.reason ?: bt.lastError?.message,
            completedSessionId = recording.sessionId?.takeIf { phase == TonightPhase.Completed || phase == TonightPhase.Interrupted }, isDemo = false)
    }

    fun findSession(sessionId: String) = sessions.firstOrNull { it.sessionId == sessionId }

    fun detailFor(sessionId: String): SessionDetail? {
        if (detailJobs[sessionId] == null) observeDetail(sessionId)
        val summary = findSession(sessionId) ?: return null
        val epochs = epochsById[sessionId].orEmpty().map { it.toObservationEpoch() }
        val gaps = gapsById[sessionId].orEmpty().map { it.toDataGap() }
        val row = entities.firstOrNull { it.id == sessionId }
        val quality = QualitySummary(level = if (epochs.isEmpty()) QualityLevel.Unknown else epochs.map { it.quality }.worstQuality(),
            coveragePercent = summary.metrics.coveragePercent, evaluatedEpochs = epochs.size.takeIf { it > 0 })
        return SessionDetail(summary, summary.metrics, epochs, gaps, emptyList(), emptyList(), quality = quality,
            protocolVersion = row?.protocolVersion, firmwareVersion = row?.firmwareVersion, appVersion = row?.appVersion,
            modelVersion = row?.modelVersion, modelAvailable = false)
    }

    fun observeDetail(sessionId: String) {
        if (detailJobs[sessionId]?.isActive == true) return
        detailJobs[sessionId] = scope.launch(Dispatchers.IO) {
            combine(library.observeGaps(sessionId), library.observeEpochs(sessionId)) { gaps, epochs -> gaps to epochs }
                .collect { (gaps, epochs) -> gapsById[sessionId] = gaps; epochsById[sessionId] = epochs }
        }
    }

    fun startScan() = acquisition.startScan().onFailure { setDeviceError(it.message) }
    fun stopScan() = acquisition.stopScan()
    fun connect(target: DeviceUi) = acquisition.connect(BleDevice(target.address, target.name, target.rssi)).onFailure { setDeviceError(it.message) }
    fun disconnect() = acquisition.disconnect()
    fun startRecording(): Result<Unit> {
        val target = device.connection.deviceAddress?.let { address -> device.devices.firstOrNull { it.address == address } }
            ?: return Result.failure(IllegalStateException("Connect to a discovered device first"))
        return acquisition.startRecording(BleDevice(target.address, target.name, target.rssi))
    }
    fun stopRecording() = acquisition.stopRecording()
    private fun setDeviceError(message: String?) { device = device.copy(lastError = message) }
    fun updateThemeMode(mode: ThemeMode) { themeMode = mode }
    fun updateDynamicColor(enabled: Boolean) { dynamicColor = enabled }
    fun saveDiary(entry: DiaryEntry) { val updated = entry.copy(updatedAtMillis = System.currentTimeMillis()); diaryStore.write(updated); diary = updated }
    fun deleteSession(sessionId: String, onComplete: (Result<Long>) -> Unit = {}) { scope.launch { val result = withContext(Dispatchers.IO) { library.deleteSession(sessionId) }; onComplete(result) } }
    fun deleteAllSessions(onComplete: (Result<Unit>) -> Unit = {}) { scope.launch { val result = withContext(Dispatchers.IO) { library.deleteAllSessions() }; onComplete(result) } }
    suspend fun exportCsv(sessionId: String, sink: OutputStream) = exporter.exportSessionCsv(sessionId, sink)
    suspend fun exportJson(sessionId: String, sink: OutputStream) = exporter.exportSessionSummaryJson(sessionId, sink)
    suspend fun exportAllJson(sink: OutputStream): Result<Int> = try {
        val zip = ZipOutputStream(sink)
        var exported = 0
        for (row in entities) {
            zip.putNextEntry(ZipEntry("${row.id}.json"))
            val result = exporter.exportSessionSummaryJson(row.id, zip)
            if (result.isFailure) return Result.failure(result.exceptionOrNull()!!)
            zip.closeEntry()
            exported++
        }
        zip.finish()
        Result.success(exported)
    } catch (error: Exception) {
        Result.failure(error)
    }
    fun close() { connection.unbind() }

}

@Composable
fun rememberFeizhelaAppState(context: Context, scope: CoroutineScope): FeizhelaAppState =
    remember(context.applicationContext) { FeizhelaAppState(context.applicationContext, scope) }

private fun String.toSessionStatus() = when (uppercase()) {
    "ACTIVE" -> SessionStatus.Active
    "COMPLETED" -> SessionStatus.Completed
    "INTERRUPTED" -> SessionStatus.Interrupted
    "FAILED" -> SessionStatus.Failed
    else -> SessionStatus.Unknown
}

private fun SignalEpochEntity.toObservationEpoch() = ObservationEpoch(
    index = epochIndex.toInt(), startMillis = startedAtMillis,
    endMillis = startedAtMillis + 30_000L, label = ObservationLabel.Unknown,
    receivedSamples = receivedSamples, expectedSamples = expectedSamples,
    coveragePercent = if (expectedSamples > 0) (100f * receivedSamples / expectedSamples).coerceIn(0f, 100f) else 0f,
    quality = when (quality.uppercase()) { "GOOD" -> QualityLevel.Good; "FAIR" -> QualityLevel.Fair; "POOR" -> QualityLevel.Poor; "REJECTED" -> QualityLevel.Rejected; else -> QualityLevel.Unknown },
    modelVersion = algorithmVersion)

private fun AcquisitionGapEntity.toDataGap() = DataGapUi(id,
    when (kind) { "DISCONNECT" -> DataGapKind.Disconnect; "BUFFER_OVERFLOW" -> DataGapKind.BufferOverflow; "SUBSCRIPTION_LOST" -> DataGapKind.SubscriptionLost; "SERVICE_RESTART" -> DataGapKind.ServiceRestart; "DEVICE_GAP" -> DataGapKind.DeviceGap; else -> DataGapKind.Unknown }, startedAtMillis, endedAtMillis, droppedEstimate, detail)

private fun List<QualityLevel>.worstQuality() = when {
    contains(QualityLevel.Rejected) -> QualityLevel.Rejected
    contains(QualityLevel.Poor) -> QualityLevel.Poor
    contains(QualityLevel.Fair) -> QualityLevel.Fair
    contains(QualityLevel.Good) -> QualityLevel.Good
    else -> QualityLevel.Unknown
}
