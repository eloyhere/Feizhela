package com.feizhela.headband.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
private const val BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"
private const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"

/**
 * JVM tests for [BluetoothPermissions]. Permission names are pinned as literal platform strings,
 * so these tests also catch an accidental switch to a different permission constant.
 */
class BluetoothPermissionsTest {

    @Test
    fun scanPermissionsMatchesThePlatformSplit() {
        mapOf(
            24 to listOf(ACCESS_FINE_LOCATION),
            30 to listOf(ACCESS_FINE_LOCATION),
            31 to listOf(BLUETOOTH_SCAN),
            33 to listOf(BLUETOOTH_SCAN),
            35 to listOf(BLUETOOTH_SCAN),
        ).forEach { (sdk, expected) ->
            assertEquals("scan permissions on sdk $sdk", expected, BluetoothPermissions.scanPermissions(sdk))
        }
    }

    @Test
    fun connectPermissionsMatchesThePlatformSplit() {
        mapOf(
            24 to emptyList<String>(),
            30 to emptyList<String>(),
            31 to listOf(BLUETOOTH_CONNECT),
            33 to listOf(BLUETOOTH_CONNECT),
            35 to listOf(BLUETOOTH_CONNECT),
        ).forEach { (sdk, expected) ->
            assertEquals("connect permissions on sdk $sdk", expected, BluetoothPermissions.connectPermissions(sdk))
        }
    }

    @Test
    fun requiredPermissionsMatchesThePlatformSplitInScanThenConnectOrder() {
        mapOf(
            24 to listOf(ACCESS_FINE_LOCATION),
            30 to listOf(ACCESS_FINE_LOCATION),
            31 to listOf(BLUETOOTH_SCAN, BLUETOOTH_CONNECT),
            33 to listOf(BLUETOOTH_SCAN, BLUETOOTH_CONNECT),
            35 to listOf(BLUETOOTH_SCAN, BLUETOOTH_CONNECT),
        ).forEach { (sdk, expected) ->
            assertEquals("required permissions on sdk $sdk", expected, BluetoothPermissions.requiredPermissions(sdk))
        }
    }

    @Test
    fun requiredPermissionsHasNoDuplicatesAtAnyLevel() {
        (24..35).forEach { sdk ->
            val required = BluetoothPermissions.requiredPermissions(sdk)
            assertEquals("duplicates on sdk $sdk", required.distinct(), required)
        }
    }

    @Test
    fun legacyLevelsNeverRequestBluetoothRuntimePermissions() {
        (24..30).forEach { sdk ->
            val required = BluetoothPermissions.requiredPermissions(sdk)
            assertFalse("sdk $sdk must not request BLUETOOTH_SCAN", BLUETOOTH_SCAN in required)
            assertFalse("sdk $sdk must not request BLUETOOTH_CONNECT", BLUETOOTH_CONNECT in required)
            assertEquals("sdk $sdk must scan via fine location", listOf(ACCESS_FINE_LOCATION), required)
        }
    }

    @Test
    fun modernLevelsNeverRequestFineLocation() {
        (31..35).forEach { sdk ->
            val required = BluetoothPermissions.requiredPermissions(sdk)
            assertFalse("sdk $sdk must not request ACCESS_FINE_LOCATION", ACCESS_FINE_LOCATION in required)
            assertTrue("sdk $sdk must request BLUETOOTH_SCAN", BLUETOOTH_SCAN in required)
            assertTrue("sdk $sdk must request BLUETOOTH_CONNECT", BLUETOOTH_CONNECT in required)
        }
    }

    @Test
    fun missingPermissionsReturnsEverythingWhenNothingIsGranted() {
        assertEquals(listOf(ACCESS_FINE_LOCATION), BluetoothPermissions.missingPermissions(24, emptySet()))
        assertEquals(listOf(ACCESS_FINE_LOCATION), BluetoothPermissions.missingPermissions(30, emptySet()))
        assertEquals(
            listOf(BLUETOOTH_SCAN, BLUETOOTH_CONNECT),
            BluetoothPermissions.missingPermissions(31, emptySet()),
        )
        assertEquals(
            listOf(BLUETOOTH_SCAN, BLUETOOTH_CONNECT),
            BluetoothPermissions.missingPermissions(33, emptySet()),
        )
        assertEquals(
            listOf(BLUETOOTH_SCAN, BLUETOOTH_CONNECT),
            BluetoothPermissions.missingPermissions(35, emptySet()),
        )
    }

    @Test
    fun missingPermissionsKeepsScanThenConnectOrderForPartialGrants() {
        assertEquals(
            listOf(BLUETOOTH_CONNECT),
            BluetoothPermissions.missingPermissions(33, setOf(BLUETOOTH_SCAN)),
        )
        assertEquals(
            listOf(BLUETOOTH_SCAN),
            BluetoothPermissions.missingPermissions(33, setOf(BLUETOOTH_CONNECT)),
        )
        assertEquals(
            listOf(BLUETOOTH_SCAN, BLUETOOTH_CONNECT),
            BluetoothPermissions.missingPermissions(35, setOf(ACCESS_FINE_LOCATION)),
        )
        assertEquals(
            emptyList<String>(),
            BluetoothPermissions.missingPermissions(30, setOf(ACCESS_FINE_LOCATION)),
        )
    }

    @Test
    fun missingPermissionsIsEmptyWhenEverythingIsGranted() {
        assertEquals(
            emptyList<String>(),
            BluetoothPermissions.missingPermissions(24, setOf(ACCESS_FINE_LOCATION)),
        )
        assertEquals(
            emptyList<String>(),
            BluetoothPermissions.missingPermissions(30, setOf(ACCESS_FINE_LOCATION)),
        )
        assertEquals(
            emptyList<String>(),
            BluetoothPermissions.missingPermissions(31, setOf(BLUETOOTH_SCAN, BLUETOOTH_CONNECT)),
        )
        assertEquals(
            emptyList<String>(),
            BluetoothPermissions.missingPermissions(35, setOf(BLUETOOTH_CONNECT, BLUETOOTH_SCAN)),
        )
    }
}
