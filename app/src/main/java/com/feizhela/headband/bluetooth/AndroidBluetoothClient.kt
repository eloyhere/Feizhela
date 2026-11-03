package com.feizhela.headband.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Android BLE/GATT transport. The caller requests runtime permissions. */
class AndroidBluetoothClient(
    context: Context,
    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter,
) : BluetoothClient {
    private val appContext = context.applicationContext
    private val lock = Any()

    @Volatile
    private var listener: ((BleEvent) -> Unit)? = null

    @Volatile
    private var state = BleConnectionState.Idle

    private var closed = false
    private var scannerCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var connectedDevice: BleDevice? = null
    private var servicesReady = false
    private val pendingSubscriptionIds = mutableMapOf<Pair<UUID, UUID>, MutableList<Long>>()
    private val scheduledSubscriptions = mutableSetOf<Pair<UUID, UUID>>()
    private val enabledSubscriptions = mutableSetOf<Pair<UUID, UUID>>()
    private val operations = ArrayDeque<GattOperation>()
    private var operationInFlight: GattOperation? = null
    private val nextRequestId = AtomicLong(1)

    override fun setListener(listener: ((BleEvent) -> Unit)?) {
        this.listener = listener
    }

    @SuppressLint("MissingPermission")
    override fun startScan(serviceUuids: Set<UUID>): Result<Unit> = runCatching {
        ensureOpen()
        ensureScanPermission()
        val bleScanner = adapter?.bluetoothLeScanner
            ?: error("Bluetooth LE scanner is unavailable; check that Bluetooth is enabled")
        check(state == BleConnectionState.Idle) { "BLE client must be idle before scanning" }

        val filters = serviceUuids.map { uuid ->
            ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid)).build()
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                emitDevice(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::emitDevice)
            }

            override fun onScanFailed(errorCode: Int) {
                val isCurrentScan = synchronized(lock) {
                    if (scannerCallback !== this) false else {
                        scannerCallback = null
                        true
                    }
                }
                if (!isCurrentScan) return
                changeState(BleConnectionState.Idle)
                emit(BleEvent.Error("scan", "BLE scan failed", errorCode))
            }
        }
        synchronized(lock) {
            check(!closed) { "Bluetooth client is closed" }
            scannerCallback = callback
        }
        changeState(BleConnectionState.Scanning)
        try {
            bleScanner.startScan(
                filters,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback,
            )
        } catch (error: Exception) {
            synchronized(lock) { if (scannerCallback === callback) scannerCallback = null }
            changeState(BleConnectionState.Idle)
            throw error
        }
    }.onFailure { emitError("scan", it) }

    @SuppressLint("MissingPermission")
    override fun stopScan() {
        val callback = synchronized(lock) {
            scannerCallback.also { scannerCallback = null }
        } ?: return
        runCatching {
            ensureScanPermission()
            adapter?.bluetoothLeScanner?.stopScan(callback)
        }.onFailure { emitError("stopScan", it) }
        if (state == BleConnectionState.Scanning) changeState(BleConnectionState.Idle)
    }

    @SuppressLint("MissingPermission")
    override fun connect(device: BleDevice): Result<Unit> = runCatching {
        ensureOpen()
        ensurePermission(Manifest.permission.BLUETOOTH_CONNECT)
        require(device.address.isNotBlank()) { "Device address is empty" }
        stopScan()
        closeGatt()

        val remoteDevice = adapter?.getRemoteDevice(device.address)
            ?: error("Bluetooth adapter is unavailable")
        synchronized(lock) {
            check(!closed) { "Bluetooth client is closed" }
            connectedDevice = device
            servicesReady = false
        }
        changeState(BleConnectionState.Connecting)
        val connection = remoteDevice.connectGatt(
            appContext,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE,
        ) ?: error("Unable to create GATT connection")
        synchronized(lock) {
            if (closed) {
                connection.close()
                error("Bluetooth client was closed while connecting")
            }
            gatt = connection
        }
    }.onFailure {
        synchronized(lock) { connectedDevice = null }
        failPendingSubscriptions("GATT connection could not be established")
        changeState(BleConnectionState.Idle)
        emitError("connect", it)
    }

    @SuppressLint("MissingPermission")
    override fun disconnect() {
        val connection = synchronized(lock) { gatt } ?: run {
            failPendingSubscriptions("GATT connection closed before subscription completed")
            if (state != BleConnectionState.Closed) changeState(BleConnectionState.Idle)
            return
        }
        changeState(BleConnectionState.Disconnecting)
        runCatching {
            ensurePermission(Manifest.permission.BLUETOOTH_CONNECT)
            connection.disconnect()
        }.onFailure { emitError("disconnect", it) }
    }

    override fun subscribe(serviceUuid: UUID, characteristicUuid: UUID): Result<Long> = runCatching {
        ensureOpen()
        ensurePermission(Manifest.permission.BLUETOOTH_CONNECT)
        check(state == BleConnectionState.Connecting || state == BleConnectionState.Connected) {
            "BLE device is not connecting or connected"
        }
        val key = serviceUuid to characteristicUuid
        val requestId = nextRequestId.getAndIncrement()
        val alreadyEnabled = synchronized(lock) {
            check(!closed) { "Bluetooth client is closed" }
            check(state == BleConnectionState.Connecting || state == BleConnectionState.Connected) {
                "BLE device is not connecting or connected"
            }
            if (key in enabledSubscriptions) {
                true
            } else {
                pendingSubscriptionIds.getOrPut(key) { mutableListOf() }.add(requestId)
                false
            }
        }
        if (alreadyEnabled) {
            emit(BleEvent.SubscriptionCompleted(requestId, serviceUuid, characteristicUuid, success = true))
        } else if (state == BleConnectionState.Connected) {
            scheduleSubscriptions()
        }
        requestId
    }.onFailure { emitError("subscribe", it) }

    override fun read(serviceUuid: UUID, characteristicUuid: UUID): Result<Long> = runCatching {
        ensureOpen()
        ensurePermission(Manifest.permission.BLUETOOTH_CONNECT)
        check(state == BleConnectionState.Connected && synchronized(lock) { servicesReady }) {
            "BLE device is not ready for GATT operations"
        }
        val connection = synchronized(lock) { gatt } ?: error("No active GATT connection")
        val characteristic = connection.getService(serviceUuid)?.getCharacteristic(characteristicUuid)
            ?: error("GATT characteristic $characteristicUuid was not found in service $serviceUuid")
        check(characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
            "Characteristic does not support reads"
        }
        val id = nextRequestId.getAndIncrement()
        synchronized(lock) {
            check(!closed && gatt === connection) { "GATT connection changed" }
            operations.addLast(GattOperation.Read(id, characteristic))
        }
        pumpOperations()
        id
    }.onFailure { emitError("read", it) }

    override fun write(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        value: ByteArray,
        writeType: BleWriteType,
    ): Result<Long> = runCatching {
        ensureOpen()
        ensurePermission(Manifest.permission.BLUETOOTH_CONNECT)
        check(state == BleConnectionState.Connected && synchronized(lock) { servicesReady }) {
            "BLE device is not ready for GATT operations"
        }
        val connection = synchronized(lock) { gatt } ?: error("No active GATT connection")
        val characteristic = connection.getService(serviceUuid)?.getCharacteristic(characteristicUuid)
            ?: error("GATT characteristic $characteristicUuid was not found in service $serviceUuid")
        val gattWriteType = when (writeType) {
            BleWriteType.WithResponse -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            BleWriteType.WithoutResponse -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val property = when (writeType) {
            BleWriteType.WithResponse -> BluetoothGattCharacteristic.PROPERTY_WRITE
            BleWriteType.WithoutResponse -> BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        }
        check(characteristic.properties and property != 0) {
            "Characteristic does not support $writeType"
        }

        val id = nextRequestId.getAndIncrement()
        synchronized(lock) {
            check(!closed && gatt === connection) { "GATT connection changed" }
            operations.addLast(
                GattOperation.Write(
                    id = id,
                    characteristic = characteristic,
                    value = value.copyOf(),
                    writeType = gattWriteType,
                ),
            )
        }
        pumpOperations()
        id
    }.onFailure { emitError("write", it) }

    override fun close() {
        val shouldClose = synchronized(lock) {
            if (closed) false else {
                closed = true
                true
            }
        }
        if (!shouldClose) return
        stopScan()
        closeGatt()
        changeState(BleConnectionState.Closed)
        listener = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(connection: BluetoothGatt, status: Int, newState: Int) {
            if (!isCurrent(connection)) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emit(BleEvent.Error("gatt", "GATT connection error", status))
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        changeState(BleConnectionState.Connected)
                        runCatching {
                            ensurePermission(Manifest.permission.BLUETOOTH_CONNECT)
                            check(connection.discoverServices()) { "GATT rejected service discovery" }
                        }.onFailure {
                            failPendingSubscriptions(it.message ?: "GATT rejected service discovery")
                            emitError("discoverServices", it)
                        }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    clearGatt(connection)
                    if (state != BleConnectionState.Closed) changeState(BleConnectionState.Idle)
                }
            }
        }

        override fun onServicesDiscovered(connection: BluetoothGatt, status: Int) {
            if (!isCurrent(connection)) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failPendingSubscriptions("Service discovery failed (status $status)")
                emit(BleEvent.Error("discoverServices", "Service discovery failed", status))
                return
            }
            val device = synchronized(lock) {
                servicesReady = true
                connectedDevice
            } ?: return
            val characteristics = connection.services.flatMap { service ->
                service.characteristics.map { characteristic ->
                    BleCharacteristic(service.uuid, characteristic.uuid, characteristic.properties)
                }
            }
            emit(BleEvent.ServicesDiscovered(device, characteristics))
            scheduleSubscriptions()
        }

        override fun onDescriptorWrite(
            connection: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (!isCurrent(connection)) return
            val completed = synchronized(lock) {
                if (closed || gatt !== connection) return
                val operation = operationInFlight as? GattOperation.Subscribe ?: return
                if (operation.descriptor !== descriptor) return
                operationInFlight = null
                scheduledSubscriptions.remove(operation.key)
                if (status == BluetoothGatt.GATT_SUCCESS) enabledSubscriptions.add(operation.key)
                operation to pendingSubscriptionIds.remove(operation.key).orEmpty()
            }
            val (operation, requestIds) = completed
            requestIds.forEach { requestId ->
                emit(
                    BleEvent.SubscriptionCompleted(
                        requestId = requestId,
                        serviceUuid = operation.key.first,
                        characteristicUuid = operation.key.second,
                        success = status == BluetoothGatt.GATT_SUCCESS,
                        status = status.takeIf { it != BluetoothGatt.GATT_SUCCESS },
                        message = "GATT subscription " + if (status == BluetoothGatt.GATT_SUCCESS) "enabled" else "failed",
                    ),
                )
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emit(BleEvent.Error("subscribe", "Notification subscription failed", status))
            }
            pumpOperations()
        }

        override fun onCharacteristicRead(
            connection: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (!isCurrent(connection)) return
            val operation = completeOperation<GattOperation.Read>(connection) {
                it.characteristic === characteristic
            } ?: return
            emit(
                BleEvent.ReadCompleted(
                    requestId = operation.id,
                    serviceUuid = operation.characteristic.service.uuid,
                    characteristicUuid = operation.characteristic.uuid,
                    value = value.copyOf().takeIf { status == BluetoothGatt.GATT_SUCCESS },
                    status = status.takeIf { it != BluetoothGatt.GATT_SUCCESS },
                ),
            )
            pumpOperations()
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            connection: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (!isCurrent(connection)) return
            val operation = completeOperation<GattOperation.Read>(connection) {
                it.characteristic === characteristic
            } ?: return
            emit(
                BleEvent.ReadCompleted(
                    requestId = operation.id,
                    serviceUuid = operation.characteristic.service.uuid,
                    characteristicUuid = operation.characteristic.uuid,
                    value = characteristic.value?.copyOf().takeIf { status == BluetoothGatt.GATT_SUCCESS },
                    status = status.takeIf { it != BluetoothGatt.GATT_SUCCESS },
                ),
            )
            pumpOperations()
        }

        override fun onCharacteristicWrite(
            connection: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (!isCurrent(connection)) return
            val operation = completeOperation<GattOperation.Write>(connection) {
                it.characteristic === characteristic
            } ?: return
            emit(
                BleEvent.WriteCompleted(
                    requestId = operation.id,
                    serviceUuid = operation.characteristic.service.uuid,
                    characteristicUuid = operation.characteristic.uuid,
                    success = status == BluetoothGatt.GATT_SUCCESS,
                    status = status.takeIf { it != BluetoothGatt.GATT_SUCCESS },
                ),
            )
            pumpOperations()
        }

        override fun onCharacteristicChanged(
            connection: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (isCurrent(connection)) emitNotification(characteristic, characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            connection: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (isCurrent(connection)) emitNotification(characteristic, value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun emitDevice(result: ScanResult) {
        val device = result.device
        emit(
            BleEvent.DeviceFound(
                BleDevice(
                    address = device.address,
                    name = runCatching { device.name }.getOrNull() ?: result.scanRecord?.deviceName,
                    rssi = result.rssi,
                ),
            ),
        )
    }

    private fun scheduleSubscriptions() {
        val connection = synchronized(lock) {
            if (closed || !servicesReady) return
            gatt ?: return
        }
        val requested = synchronized(lock) {
            pendingSubscriptionIds.keys.filter { key ->
                key !in enabledSubscriptions && scheduledSubscriptions.add(key)
            }
        }
        requested.forEach { key ->
            runCatching {
                val characteristic = connection.getService(key.first)?.getCharacteristic(key.second)
                    ?: error("GATT characteristic ${key.second} was not found in service ${key.first}")
                val properties = characteristic.properties
                check(
                    properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                        properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0,
                ) { "Characteristic does not support notifications or indications" }
                check(connection.setCharacteristicNotification(characteristic, true)) {
                    "Failed to enable local characteristic notifications"
                }
                val descriptor = characteristic.getDescriptor(CLIENT_CONFIGURATION_UUID)
                    ?: error("Client Characteristic Configuration descriptor is missing")
                val value = if (
                    properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 &&
                    properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0
                ) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                synchronized(lock) {
                    check(!closed && gatt === connection && servicesReady) {
                        "GATT connection changed while scheduling subscription"
                    }
                    operations.addLast(GattOperation.Subscribe(key, descriptor, value))
                }
            }.onFailure { error ->
                val failedIds = synchronized(lock) {
                    if (gatt !== connection) {
                        emptyList()
                    } else {
                        scheduledSubscriptions.remove(key)
                        pendingSubscriptionIds.remove(key).orEmpty()
                    }
                }
                failedIds.forEach { requestId ->
                    emit(
                        BleEvent.SubscriptionCompleted(
                            requestId = requestId,
                            serviceUuid = key.first,
                            characteristicUuid = key.second,
                            success = false,
                            message = error.message ?: "Unable to queue subscription",
                        ),
                    )
                }
                emitError("subscribe", error)
            }
        }
        pumpOperations()
    }

    @SuppressLint("MissingPermission")
    private fun pumpOperations() {
        val next: Pair<BluetoothGatt, GattOperation> = synchronized(lock) {
            if (closed || operationInFlight != null || operations.isEmpty()) return
            val connection = gatt ?: return
            val operation = operations.removeFirst()
            operationInFlight = operation
            connection to operation
        }
        runCatching {
            ensurePermission(Manifest.permission.BLUETOOTH_CONNECT)
            when (val operation = next.second) {
                is GattOperation.Subscribe -> {
                    val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        next.first.writeDescriptor(operation.descriptor, operation.value) == BluetoothGatt.GATT_SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        run {
                            operation.descriptor.value = operation.value
                            next.first.writeDescriptor(operation.descriptor)
                        }
                    }
                    check(accepted) { "GATT rejected descriptor write" }
                }
                is GattOperation.Read -> {
                    check(next.first.readCharacteristic(operation.characteristic)) {
                        "GATT rejected characteristic read"
                    }
                }
                is GattOperation.Write -> {
                    operation.characteristic.writeType = operation.writeType
                    val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        next.first.writeCharacteristic(
                            operation.characteristic,
                            operation.value,
                            operation.writeType,
                        ) == BluetoothGatt.GATT_SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        run {
                            operation.characteristic.value = operation.value
                            next.first.writeCharacteristic(operation.characteristic)
                        }
                    }
                    check(accepted) { "GATT rejected characteristic write" }
                }
            }
        }.onFailure { error ->
            val failedOperation = next.second
            val failure = synchronized(lock) {
                if (gatt !== next.first || operationInFlight !== failedOperation) {
                    null
                } else {
                    operationInFlight = null
                    val requestIds = if (failedOperation is GattOperation.Subscribe) {
                        scheduledSubscriptions.remove(failedOperation.key)
                        pendingSubscriptionIds.remove(failedOperation.key).orEmpty()
                    } else {
                        emptyList()
                    }
                    requestIds
                }
            } ?: return@onFailure
            if (failedOperation is GattOperation.Read) {
                val read = failedOperation
                emit(
                    BleEvent.ReadCompleted(
                        requestId = read.id,
                        serviceUuid = read.characteristic.service.uuid,
                        characteristicUuid = read.characteristic.uuid,
                        value = null,
                        message = error.message ?: "GATT rejected characteristic read",
                    ),
                )
            } else if (failedOperation is GattOperation.Write) {
                val write = failedOperation
                emit(
                    BleEvent.WriteCompleted(
                        requestId = write.id,
                        serviceUuid = write.characteristic.service.uuid,
                        characteristicUuid = write.characteristic.uuid,
                        success = false,
                        status = null,
                        message = error.message ?: "GATT rejected characteristic write",
                    ),
                )
            } else if (failedOperation is GattOperation.Subscribe) {
                failure.forEach { requestId ->
                    emit(
                        BleEvent.SubscriptionCompleted(
                            requestId = requestId,
                            serviceUuid = failedOperation.key.first,
                            characteristicUuid = failedOperation.key.second,
                            success = false,
                            message = error.message ?: "GATT rejected descriptor write",
                        ),
                    )
                }
                emitError("subscribe", error)
            }
            pumpOperations()
        }
    }

    private inline fun <reified T : GattOperation> completeOperation(
        connection: BluetoothGatt,
        matches: (T) -> Boolean = { true },
    ): T? = synchronized(lock) {
        if (gatt !== connection) return@synchronized null
        val operation = operationInFlight ?: return@synchronized null
        if (operation !is T || !matches(operation)) return@synchronized null
        operationInFlight = null
        operation
    }

    private fun emitNotification(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val serviceUuid = characteristic.service?.uuid ?: return
        emit(
            BleEvent.NotificationReceived(
                serviceUuid = serviceUuid,
                characteristicUuid = characteristic.uuid,
                value = value.copyOf(),
                receivedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    private fun ensureScanPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        ensurePermission(permission)
    }

    private fun ensurePermission(permission: String) {
        if (permission == Manifest.permission.BLUETOOTH_CONNECT &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S
        ) return
        if (appContext.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Missing runtime permission: $permission")
        }
    }

    private fun ensureOpen() {
        check(synchronized(lock) { !closed }) { "Bluetooth client is closed" }
    }

    private fun isCurrent(connection: BluetoothGatt): Boolean =
        synchronized(lock) { !closed && gatt === connection }

    @SuppressLint("MissingPermission")
    private fun clearGatt(expected: BluetoothGatt) {
        val cancelled = synchronized(lock) {
            if (gatt !== expected) return
            gatt = null
            connectedDevice = null
            servicesReady = false
            scheduledSubscriptions.clear()
            enabledSubscriptions.clear()
            takePendingOperations() to takePendingSubscriptions()
        }
        emitCancelledOperations(cancelled.first)
        emitCancelledSubscriptions(cancelled.second, "GATT connection closed before subscription completed")
        runCatching { expected.close() }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        val closing = synchronized(lock) {
            val current = gatt
            gatt = null
            connectedDevice = null
            servicesReady = false
            scheduledSubscriptions.clear()
            enabledSubscriptions.clear()
            current to (takePendingOperations() to takePendingSubscriptions())
        }
        emitCancelledOperations(closing.second.first)
        emitCancelledSubscriptions(closing.second.second, "GATT connection closed before subscription completed")
        val connection = closing.first ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ensurePermission(Manifest.permission.BLUETOOTH_CONNECT)
            }
            connection.disconnect()
        }.onFailure { emitError("closeGatt", it) }
        runCatching { connection.close() }.onFailure { emitError("closeGatt", it) }
    }

    private fun failPendingSubscriptions(message: String) {
        val pending = synchronized(lock) {
            scheduledSubscriptions.clear()
            takePendingSubscriptions()
        }
        emitCancelledSubscriptions(pending, message)
    }

    private fun takePendingSubscriptions(): List<Pair<Pair<UUID, UUID>, Long>> = buildList {
        pendingSubscriptionIds.forEach { (key, requestIds) ->
            requestIds.forEach { requestId -> add(key to requestId) }
        }
        pendingSubscriptionIds.clear()
    }

    private fun emitCancelledSubscriptions(
        cancelled: List<Pair<Pair<UUID, UUID>, Long>>,
        message: String,
    ) {
        cancelled.forEach { (key, requestId) ->
            emit(
                BleEvent.SubscriptionCompleted(
                    requestId = requestId,
                    serviceUuid = key.first,
                    characteristicUuid = key.second,
                    success = false,
                    message = message,
                ),
            )
        }
    }

    private fun takePendingOperations(): List<GattOperation> = buildList {
        operationInFlight?.let(::add)
        addAll(operations)
        operationInFlight = null
        operations.clear()
    }

    private fun emitCancelledOperations(cancelled: List<GattOperation>) {
        cancelled.forEach { operation ->
            when (operation) {
                is GattOperation.Read -> emit(
                    BleEvent.ReadCompleted(
                        requestId = operation.id,
                        serviceUuid = operation.characteristic.service.uuid,
                        characteristicUuid = operation.characteristic.uuid,
                        value = null,
                        message = "GATT connection closed before read completed",
                    ),
                )
                is GattOperation.Write -> emit(
                    BleEvent.WriteCompleted(
                        requestId = operation.id,
                        serviceUuid = operation.characteristic.service.uuid,
                        characteristicUuid = operation.characteristic.uuid,
                        success = false,
                        message = "GATT connection closed before write completed",
                    ),
                )
                is GattOperation.Subscribe -> Unit
            }
        }
    }

    private fun changeState(next: BleConnectionState) {
        if (state == next) return
        state = next
        emit(BleEvent.StateChanged(next))
    }

    private fun emit(event: BleEvent) {
        listener?.invoke(event)
    }

    private fun emitError(operation: String, error: Throwable) {
        emit(
            BleEvent.Error(
                operation = operation,
                message = error.message ?: error::class.java.simpleName,
                cause = error,
            ),
        )
    }

    private sealed interface GattOperation {
        data class Read(
            val id: Long,
            val characteristic: BluetoothGattCharacteristic,
        ) : GattOperation

        data class Subscribe(
            val key: Pair<UUID, UUID>,
            val descriptor: BluetoothGattDescriptor,
            val value: ByteArray,
        ) : GattOperation

        data class Write(
            val id: Long,
            val characteristic: BluetoothGattCharacteristic,
            val value: ByteArray,
            val writeType: Int,
        ) : GattOperation
    }

    companion object {
        val CLIENT_CONFIGURATION_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
