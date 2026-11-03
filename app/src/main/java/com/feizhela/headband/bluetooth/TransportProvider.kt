package com.feizhela.headband.bluetooth

import android.content.Context
import com.feizhela.headband.BuildConfig

/**
 * The one injection seam for the BLE transport.
 *
 * Production builds always fall through to [AndroidBluetoothClient]. Only the debug variant may
 * install a factory: the simulated transport lives in `src/debug`, so it is absent from release
 * compilations, and the [BuildConfig.DEBUG] guard additionally ignores any factory that a debug
 * build might have left behind at runtime.
 */
object TransportProvider {
    /** Overridable only by debug tests/simulation entry points; release always uses real BLE. */
    @Volatile
    var factory: (() -> BluetoothClient)? = null

    fun create(context: Context): BluetoothClient =
        (if (BuildConfig.DEBUG) factory?.invoke() else null) ?: AndroidBluetoothClient(context)

    /** Restores the real transport. Safe to call repeatedly. */
    fun reset() {
        factory = null
    }
}
