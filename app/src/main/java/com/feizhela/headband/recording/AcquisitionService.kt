package com.feizhela.headband.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.feizhela.headband.MainActivity
import com.feizhela.headband.bluetooth.BleDevice
import com.feizhela.headband.bluetooth.BluetoothRepository
import com.feizhela.headband.bluetooth.DefaultBluetoothRepository
import com.feizhela.headband.bluetooth.FlowBluetoothDataSource
import com.feizhela.headband.bluetooth.TransportProvider
import com.feizhela.headband.storage.RecordingDatabase
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Owns the transport and recording independently of an Activity or a bound client. */
class AcquisitionService : Service() {
    private val serviceJob = SupervisorJob()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope: CoroutineScope = CoroutineScope(serviceJob + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        Log.e(TAG, "Acquisition command failed", error)
        if (!destroying) mainHandler.post {
            runCatching { updateNotification("采集操作失败，请打开应用查看并重试") }
                .onFailure { Log.e(TAG, "Failed to update acquisition notification", it) }
        }
    })
    private val commands = Mutex()
    private val binder = LocalBinder()
    private lateinit var bluetooth: BluetoothRepository
    private lateinit var controller: RecordingSessionRepository
    private lateinit var recovery: Deferred<Result<Unit>>
    private lateinit var analysis: SignalAnalysisRepository
    private var analysisJob: Job? = null

    @Volatile private var destroying = false
    @Volatile private var stopping = false
    private var foreground = false
    private var startGeneration = 0
    private var notificationText = READY_TEXT

    inner class LocalBinder : Binder() {
        /** Observation only: callers cannot replace the service's acquisition owner. */
        val state: StateFlow<RecordingState> get() = controller.state
        val analysisState: StateFlow<SignalAnalysisRepository.AnalysisState> get() = analysis.state
        val bluetoothState get() = bluetooth.state
        val discoveredDevices get() = bluetooth.discoveredDevices
        val discoveredCharacteristics get() = bluetooth.discoveredCharacteristics

        fun startScan(): Result<Unit> = bluetooth.startScan()
        fun stopScan() = bluetooth.stopScan()
        fun connect(device: BleDevice): Result<Unit> {
            check(controller.state.value.phase !in setOf(RecordingPhase.Starting, RecordingPhase.Recording, RecordingPhase.Stopping)) {
                "Stop the active recording before changing devices"
            }
            return bluetooth.connect(device)
        }
        fun disconnect() = bluetooth.disconnect()

        /** Permissions and foreground-start eligibility must be arranged by the caller. */
        fun startRecording(device: BleDevice, serviceUuid: UUID, characteristicUuid: UUID): Job {
            check(!destroying && !stopping) { "Acquisition service is stopping" }
            // Establish a started-service lifetime even when the client originally only bound.
            startForeground(this@AcquisitionService)
            return scope.launch {
                commands.withLock {
                    check(!destroying && !stopping) { "Acquisition service is stopping" }
                    recovery.await().getOrThrow()
                    withContext(Dispatchers.Main) { updateNotification("正在连接设备并准备采集，请稍候") }
                    val result = controller.start(device, serviceUuid, characteristicUuid)
                    withContext(Dispatchers.Main) {
                        updateNotification(
                            if (result.isSuccess) "采集会话已启动，请在应用查看连接和录制状态"
                            else "未能开始采集，请在应用查看原因并重试",
                        )
                    }
                }
            }
        }

        /** Flushes the current recording; the foreground owner remains available for another one. */
        fun stopRecording(): Job = scope.launch {
            commands.withLock {
                recovery.await().getOrThrow()
                controller.stop()
                withContext(Dispatchers.Main) { updateNotification("采集已停止，服务待命") }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val client = TransportProvider.create(applicationContext)
        bluetooth = DefaultBluetoothRepository(FlowBluetoothDataSource(client), scope)
        val database = RecordingDatabase.open(applicationContext)
        controller = RecordingSessionRepository(database.recordingDao, bluetooth, scope)
        analysis = SignalAnalysisRepository(database.recordingDao, database.signalEpochDao)
        // Every start waits for this so recovery cannot interrupt a newly created ACTIVE session.
        // The controller marks old ACTIVE sessions INTERRUPTED with reason PROCESS_RESTART.
        recovery = scope.async {
            try {
                controller.recoverInterruptedSessions()
                // Start independently: replay and CPU work never hold up a new BLE connection.
                analysisJob = analysis.follow(scope, controller.state)
                Result.success(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Failed to recover interrupted recordings", error)
                Result.failure(error)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_FOREGROUND -> {
                startGeneration += 1
                // Foreground readiness says nothing about a GATT connection or subscription.
                ensureForeground()
            }
            ACTION_STOP -> {
                stopping = true
                val generation = startGeneration
                scope.launch {
                    try {
                        commands.withLock {
                            recovery.await().getOrThrow()
                            controller.stop()
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.e(TAG, "Failed to stop recording cleanly", error)
                        // Do not report a successful stop when flushing failed.
                        runCatching { controller.interrupt("STOP_FAILED") }
                            .onFailure { Log.e(TAG, "Failed to mark interrupted recording", it) }
                    } finally {
                        withContext(NonCancellable + Dispatchers.Main) {
                            stopping = false
                            if (!destroying && generation == startGeneration) {
                                removeForeground()
                                stopSelfResult(startId)
                            }
                        }
                    }
                }
            }
            else -> if (!foreground) stopSelfResult(startId)
        }
        // Process death is recovered durably, never by pretending a previous connection survived.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroying = true
        removeForeground()
        // Keep the IO owner alive until pending batches and the INTERRUPTED marker are written.
        scope.launch(NonCancellable) {
            try {
                commands.withLock {
                    recovery.await().getOrThrow()
                    controller.interrupt("SERVICE_DESTROYED")
                }
                // Bound the final replay wait; durable input lets the next owner finish it.
                withTimeoutOrNull(5_000) { analysis.drainFinal() }
            } catch (error: Exception) {
                Log.e(TAG, "Failed to finalize recording during service destruction", error)
            } finally {
                try {
                    bluetooth.disconnect()
                } finally {
                    try {
                        bluetooth.close()
                    } finally {
                        scope.cancel()
                    }
                }
            }
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "头环蓝牙采集", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "显示蓝牙采集服务状态，并可停止采集"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun ensureForeground() {
        val notification = buildNotification()
        // POST_NOTIFICATIONS is not a prerequisite for a foreground service. Always supply
        // its notification: Android may show it in Task Manager when drawer permission is denied.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foreground = true
    }

    private fun updateNotification(text: String) {
        if (destroying) return
        notificationText = text
        if (foreground) {
            // Reuse startForeground instead of gating acquisition on notification permission.
            ensureForeground()
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AcquisitionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("头环采集服务")
            .setContentText(notificationText)
            .setStyle(Notification.BigTextStyle().bigText(notificationText))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "停止采集", stop).build())
            .build()
    }

    private fun removeForeground() {
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
    }

    companion object {
        const val ACTION_START_FOREGROUND = "com.feizhela.headband.recording.START_FOREGROUND"
        const val ACTION_STOP = "com.feizhela.headband.recording.STOP"
        private const val TAG = "AcquisitionService"
        private const val CHANNEL_ID = "headband_acquisition"
        private const val NOTIFICATION_ID = 1001
        private const val READY_TEXT = "服务已就绪，等待选择设备；尚未开始连接或采集"

        /** Call from an eligible foreground context after obtaining Bluetooth permissions. */
        fun startForeground(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, AcquisitionService::class.java).setAction(ACTION_START_FOREGROUND)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent)
            else app.startService(intent)
        }

        /** Stops through the service so the recording is flushed before stopSelf. */
        fun stop(context: Context) {
            val app = context.applicationContext
            app.startService(Intent(app, AcquisitionService::class.java).setAction(ACTION_STOP))
        }
    }
}
