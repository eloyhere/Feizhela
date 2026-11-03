package com.feizhela.headband.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.ui.graphics.vector.ImageVector
import com.feizhela.headband.R

/** Product-domain routes. Records pass a stable session id, never arrays of samples. */
object FeizhelaRoutes {
    const val TONIGHT = "tonight"
    const val RECORDS = "records"
    const val DIARY = "diary"
    const val DEVICE = "device"
    const val SETTINGS = "settings"

    const val RECORD_DETAIL = "records/{sessionId}"
    const val SESSION_ID_ARG = "sessionId"

    val topLevel = listOf(TONIGHT, RECORDS, DIARY, DEVICE)

    fun recordDetail(sessionId: String): String = "records/$sessionId"
}

/** The four phone bottom-bar entries. Settings lives in the top app bar, not a fifth tab. */
enum class BottomDestination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
) {
    Tonight(FeizhelaRoutes.TONIGHT, R.string.nav_tonight, Icons.Filled.Home),
    Records(FeizhelaRoutes.RECORDS, R.string.nav_records, Icons.Filled.List),
    Diary(FeizhelaRoutes.DIARY, R.string.nav_diary, Icons.Filled.Edit),
    Device(FeizhelaRoutes.DEVICE, R.string.nav_device, Icons.Filled.Build),
}
