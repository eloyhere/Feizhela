package com.feizhela.headband.permission

import android.Manifest

/**
 * Runtime permission contract for BLE scanning and GATT access.
 *
 * The contract is a pure function of the platform API level: it takes no `Context`, reads no
 * runtime state and never shows a permission dialog. Callers collect [requiredPermissions] (or the
 * narrower [scanPermissions] / [connectPermissions]), then use [missingPermissions] to see what is
 * still outstanding before calling the BLE transport.
 *
 * Mirrors `AndroidBluetoothClient.ensureScanPermission()` and the `BLUETOOTH_CONNECT` guard in
 * `ensurePermission()`, and the declarations in `AndroidManifest.xml`:
 * `ACCESS_FINE_LOCATION` is capped at API 30, while `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` exist
 * from API 31 (Android 12, `Build.VERSION_CODES.S`).
 */
object BluetoothPermissions {

    /** API 31, `Build.VERSION_CODES.S`: the first level with the BLUETOOTH_* runtime permissions. */
    private const val SDK_BLUETOOTH_RUNTIME_PERMISSIONS = 31

    private val fineLocation = listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    private val bluetoothScan = listOf(Manifest.permission.BLUETOOTH_SCAN)
    private val bluetoothConnect = listOf(Manifest.permission.BLUETOOTH_CONNECT)
    private val noPermissions = emptyList<String>()

    /**
     * Runtime permissions needed to scan for real on this SDK level.
     *
     * API <= 30: [Manifest.permission.ACCESS_FINE_LOCATION], because the platform gates BLE scans
     * behind location. API >= 31: [Manifest.permission.BLUETOOTH_SCAN].
     */
    fun scanPermissions(sdkInt: Int): List<String> =
        if (sdkInt >= SDK_BLUETOOTH_RUNTIME_PERMISSIONS) bluetoothScan else fineLocation

    /**
     * Runtime permissions needed to connect, subscribe, read and write.
     *
     * API <= 30: none, since `BLUETOOTH` / `BLUETOOTH_ADMIN` are install-time permissions there.
     * API >= 31: [Manifest.permission.BLUETOOTH_CONNECT].
     */
    fun connectPermissions(sdkInt: Int): List<String> =
        if (sdkInt >= SDK_BLUETOOTH_RUNTIME_PERMISSIONS) bluetoothConnect else noPermissions

    /**
     * Everything acquisition needs at runtime on this SDK level.
     *
     * The list is de-duplicated and kept in a stable order: scan permissions first, then connect
     * permissions.
     */
    fun requiredPermissions(sdkInt: Int): List<String> =
        (scanPermissions(sdkInt) + connectPermissions(sdkInt)).distinct()

    /**
     * The subset of [requiredPermissions] that is not in [granted], preserving that same order.
     *
     * [granted] is supplied by the caller (for example from `Context.checkSelfPermission`) so the
     * contract stays pure and testable on the JVM.
     */
    fun missingPermissions(sdkInt: Int, granted: Set<String>): List<String> =
        requiredPermissions(sdkInt).filterNot(granted::contains)
}
