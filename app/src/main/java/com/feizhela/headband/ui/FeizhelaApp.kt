package com.feizhela.headband.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.feizhela.headband.R
import com.feizhela.headband.ui.components.ConnectionIndicator
import com.feizhela.headband.ui.components.EmptyState
import com.feizhela.headband.ui.model.ConnectionUiState
import com.feizhela.headband.ui.model.ConnectionUiStatus
import com.feizhela.headband.ui.model.SaveState
import com.feizhela.headband.ui.model.SessionDateFilter
import com.feizhela.headband.ui.model.SessionStatus
import com.feizhela.headband.ui.model.SessionStatusFilter
import com.feizhela.headband.ui.model.TonightPhase
import com.feizhela.headband.ui.navigation.BottomDestination
import com.feizhela.headband.ui.navigation.FeizhelaRoutes
import com.feizhela.headband.ui.screens.DeviceScreen
import com.feizhela.headband.ui.screens.DiaryScreen
import com.feizhela.headband.ui.screens.RecordsScreen
import com.feizhela.headband.ui.screens.SessionDetailScreen
import com.feizhela.headband.ui.screens.SettingsScreen
import com.feizhela.headband.ui.screens.TonightScreen
import com.feizhela.headband.ui.state.FeizhelaAppState
import com.feizhela.headband.ui.state.rememberFeizhelaAppState
import kotlinx.coroutines.launch

/**
 * App entry: theme + adaptive navigation shell + five product screens.
 *
 * Phone: bottom navigation with 今夜 / 记录 / 日记 / 设备; settings is a top-app-bar action.
 * Tablet/landscape (maxWidth >= 600 dp): NavigationRail with the same four destinations and a
 * two-pane records list/detail. The records detail route is `records/{sessionId}`; the bottom bar
 * is hidden on that route (documented choice: detail is a drill-down task, the top-app-bar back
 * arrow returns to the list, and tablets keep the list visible in the left pane instead).
 *
 * State is owned by [FeizhelaAppState] (demo for now). Screens receive snapshots + callbacks.
 * The LaunchedEffects below only simulate service latency/ticking for the demo; they disappear
 * together with the demo data when the real service adapter is wired.
 */
@Composable
fun FeizhelaApp() {
    val appState = rememberFeizhelaAppState(LocalContext.current, rememberCoroutineScope())
    DisposableEffect(appState) { onDispose { appState.close() } }
    FeizhelaTheme(
        themeMode = appState.themeMode,
        dynamicColor = appState.dynamicColor,
    ) {
        FeizhelaAppContent(appState = appState)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeizhelaAppContent(appState: FeizhelaAppState) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: FeizhelaRoutes.TONIGHT
    val isDetailRoute = currentRoute == FeizhelaRoutes.RECORD_DETAIL
    val isSettingsRoute = currentRoute == FeizhelaRoutes.SETTINGS
    var lastTopLevelRoute by rememberSaveable { mutableStateOf(FeizhelaRoutes.TONIGHT) }
    val selectedTabRoute = when {
        isDetailRoute -> FeizhelaRoutes.RECORDS
        isSettingsRoute -> lastTopLevelRoute
        else -> currentRoute
    }
    LaunchedEffect(currentRoute) {
        if (currentRoute in FeizhelaRoutes.topLevel) lastTopLevelRoute = currentRoute
        else if (isDetailRoute) lastTopLevelRoute = FeizhelaRoutes.RECORDS
    }

    var statusFilterName by rememberSaveable { mutableStateOf(SessionStatusFilter.All.name) }
    var dateFilterName by rememberSaveable { mutableStateOf(SessionDateFilter.All.name) }
    val statusFilter = SessionStatusFilter.entries
        .firstOrNull { it.name == statusFilterName } ?: SessionStatusFilter.All
    val dateFilter = SessionDateFilter.entries
        .firstOrNull { it.name == dateFilterName } ?: SessionDateFilter.All
    val nowMillis = System.currentTimeMillis()
    val context = LocalContext.current
    var pendingScan by remember { mutableStateOf(false) }
    var pendingExportId by remember { mutableStateOf<String?>(null) }
    val showMessage: (String) -> Unit = { text ->
        scope.launch { snackbarHostState.showSnackbar(text) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it } && pendingScan) appState.startScan().onFailure { showMessage(it.message ?: "蓝牙扫描失败") }
        else if (grants.values.any { !it }) showMessage("未授予蓝牙权限，无法扫描或连接设备")
        pendingScan = false
    }
    fun scanWithPermissions() {
        val required = com.feizhela.headband.permission.BluetoothPermissions.requiredPermissions(android.os.Build.VERSION.SDK_INT)
        val missing = required.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) appState.startScan().onFailure { showMessage(it.message ?: "蓝牙扫描失败") }
        else { pendingScan = true; permissionLauncher.launch(missing.toTypedArray()) }
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val sessionId = pendingExportId
            val result = runCatching {
                val sink = context.contentResolver.openOutputStream(uri) ?: error("无法打开目标文件")
                sink.use { output -> appState.exportCsv(sessionId ?: error("未选择记录"), output).getOrThrow() }
            }
            showMessage(result.fold({ "已导出 $it 包" }, { "导出失败：${it.message}" }))
        }
        pendingExportId = null
    }
    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            val result = runCatching {
                val sink = context.contentResolver.openOutputStream(uri) ?: error("无法打开目标文件")
                sink.use { output -> appState.exportAllJson(output).getOrThrow() }
            }
            showMessage(result.fold({ "已导出 $it 条摘要" }, { "导出失败：${it.message}" }))
        }
    }

    LaunchedEffect(Unit) { appState.acquisition.serviceAvailable.collect { } }

    // ---- Messages resolved here so callbacks stay non-composable. ----
    val startedToast = stringResource(R.string.toast_started)
    val noDeviceToast = stringResource(R.string.tonight_error_start_without_device)
    val stoppedToast = stringResource(R.string.toast_stopped)
    val scanStartedToast = stringResource(R.string.device_scan_started)
    val scanStoppedToast = stringResource(R.string.device_scan_stopped)
    val disconnectedToast = stringResource(R.string.device_disconnected_toast)
    val exportToast = stringResource(R.string.detail_export_demo_toast)
    val deleteToast = stringResource(R.string.detail_delete_demo_toast)
    val exportAllToast = stringResource(R.string.settings_export_toast)
    val deleteAllToast = stringResource(R.string.settings_deleted_toast)
    val diarySavedToast = stringResource(R.string.diary_saved_toast)
    val saveArchivedToast = "采集数据由服务持续写入本机，停止后请在记录页查看。"
    val deviceError = "设备操作失败"

    val statusFilterCallback: (SessionStatusFilter) -> Unit = { statusFilterName = it.name }
    val dateFilterCallback: (SessionDateFilter) -> Unit = { dateFilterName = it.name }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        // Reserve at least 320 dp for the list and 420 dp for readable detail content.
        val twoPane = maxWidth >= 900.dp
        Row(modifier = Modifier.fillMaxSize()) {
            if (wide) {
                FeizhelaNavigationRail(
                    selectedRoute = selectedTabRoute,
                    onSelect = { route -> navController.navigateTopLevel(route) },
                )
            }
            Scaffold(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    FeizhelaTopBar(
                        title = titleForRoute(currentRoute),
                        connection = appState.device.connection,
                        showBack = isDetailRoute || isSettingsRoute,
                        onBack = { navController.popBackStack() },
                        onOpenDevice = { navController.navigateTopLevel(FeizhelaRoutes.DEVICE) },
                        onOpenSettings = {
                            navController.navigate(FeizhelaRoutes.SETTINGS) { launchSingleTop = true }
                        },
                        showSettings = !isSettingsRoute,
                    )
                },
                bottomBar = {
                    if (!wide && !isDetailRoute) {
                        NavigationBar {
                            BottomDestination.entries.forEach { destination ->
                                NavigationBarItem(
                                    selected = selectedTabRoute == destination.route,
                                    onClick = { navController.navigateTopLevel(destination.route) },
                                    icon = {
                                        Icon(
                                            imageVector = destination.icon,
                                            contentDescription = null,
                                        )
                                    },
                                    label = { Text(stringResource(destination.labelRes)) },
                                    modifier = Modifier.testTag(
                                        "nav_${destination.name.lowercase()}",
                                    ),
                                )
                            }
                        }
                    }
                },
                snackbarHost = { SnackbarHost(snackbarHostState) },
            ) { innerPadding ->
                NavHost(
                    navController = navController,
                    startDestination = FeizhelaRoutes.TONIGHT,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .consumeWindowInsets(innerPadding),
                ) {
                    composable(FeizhelaRoutes.TONIGHT) {
                        TonightScreen(
                            state = appState.tonight,
                            onStart = {
                                appState.startRecording().onSuccess { showMessage(startedToast) }
                                    .onFailure { showMessage(it.message ?: noDeviceToast) }
                            },
                            onStop = {
                                appState.stopRecording()
                                showMessage(stoppedToast)
                            },
                            onSave = { showMessage(saveArchivedToast) },
                            onOpenDevice = {
                                navController.navigateTopLevel(FeizhelaRoutes.DEVICE)
                            },
                            onOpenSessionDetail = { sessionId ->
                                navController.navigate(FeizhelaRoutes.recordDetail(sessionId))
                            },
                        )
                    }

                    composable(FeizhelaRoutes.RECORDS) {
                        if (twoPane) {
                            var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
                            TwoPaneRecords(
                                appState = appState,
                                statusFilter = statusFilter,
                                dateFilter = dateFilter,
                                onStatusFilterChange = statusFilterCallback,
                                onDateFilterChange = dateFilterCallback,
                                selectedSessionId = selectedId,
                                onSelect = { selectedId = it },
                                onExport = { id -> pendingExportId = id; csvLauncher.launch("$id.csv") },
                                onDelete = { id ->
                                    appState.deleteSession(id) { result ->
                                        if (result.isSuccess) { selectedId = null; showMessage("记录已删除") }
                                        else showMessage("删除失败：${result.exceptionOrNull()?.message}")
                                    }
                                },
                                nowMillis = nowMillis,
                            )
                        } else {
                            RecordsScreen(
                                sessions = appState.sessions,
                                statusFilter = statusFilter,
                                dateFilter = dateFilter,
                                onStatusFilterChange = statusFilterCallback,
                                onDateFilterChange = dateFilterCallback,
                                onOpenSession = { sessionId ->
                                    navController.navigate(FeizhelaRoutes.recordDetail(sessionId))
                                },
                                nowMillis = nowMillis,
                            )
                        }
                    }

                    composable(
                        route = FeizhelaRoutes.RECORD_DETAIL,
                        arguments = listOf(
                            navArgument(FeizhelaRoutes.SESSION_ID_ARG) {
                                type = NavType.StringType
                            },
                        ),
                    ) { entry ->
                        val sessionId = entry.arguments
                            ?.getString(FeizhelaRoutes.SESSION_ID_ARG)
                            .orEmpty()
                        if (twoPane) {
                            var selectedId by rememberSaveable(sessionId) {
                                mutableStateOf<String?>(sessionId)
                            }
                            TwoPaneRecords(
                                appState = appState,
                                statusFilter = statusFilter,
                                dateFilter = dateFilter,
                                onStatusFilterChange = statusFilterCallback,
                                onDateFilterChange = dateFilterCallback,
                                selectedSessionId = selectedId,
                                onSelect = { selectedId = it },
                                onExport = { id -> pendingExportId = id; csvLauncher.launch("$id.csv") },
                                onDelete = { id ->
                                    appState.deleteSession(id) { result ->
                                        if (result.isSuccess) { selectedId = null; showMessage("记录已删除") }
                                        else showMessage("删除失败：${result.exceptionOrNull()?.message}")
                                    }
                                },
                                nowMillis = nowMillis,
                            )
                        } else {
                            SessionDetailScreen(
                                detail = appState.detailFor(sessionId),
                                onExport = { pendingExportId = sessionId; csvLauncher.launch("$sessionId.csv") },
                                onDelete = {
                                    appState.deleteSession(sessionId) { result ->
                                        if (result.isSuccess) { showMessage("记录已删除"); navController.popBackStack() }
                                        else showMessage("删除失败：${result.exceptionOrNull()?.message}")
                                    }
                                },
                            )
                        }
                    }

                    composable(FeizhelaRoutes.DIARY) {
                        DiaryScreen(
                            diary = appState.diary,
                            observed = appState.sessions.firstOrNull {
                                it.status == SessionStatus.Completed
                            },
                            onSave = { entry ->
                                appState.saveDiary(entry)
                                showMessage(diarySavedToast)
                            },
                        )
                    }

                    composable(FeizhelaRoutes.DEVICE) {
                        DeviceScreen(
                            state = appState.device,
                            onScan = { scanWithPermissions() },
                            onStopScan = {
                                appState.stopScan()
                                showMessage(scanStoppedToast)
                            },
                            onConnect = { device ->
                                appState.connect(device).onFailure { showMessage(it.message ?: deviceError) }
                            },
                            onDisconnect = {
                                appState.disconnect()
                                showMessage(disconnectedToast)
                            },
                        )
                    }

                    composable(FeizhelaRoutes.SETTINGS) {
                        SettingsScreen(
                            themeMode = appState.themeMode,
                            dynamicColor = appState.dynamicColor,
                            onThemeModeChange = appState::updateThemeMode,
                            onDynamicColorChange = appState::updateDynamicColor,
                            sessionCount = appState.sessions.size,
                            onExportAll = { showMessage(exportAllToast) },
                            onDeleteAll = {
                                appState.deleteAllSessions()
                                showMessage(deleteAllToast)
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeizhelaTopBar(
    title: String,
    connection: ConnectionUiState,
    showBack: Boolean,
    onBack: () -> Unit,
    onOpenDevice: () -> Unit,
    onOpenSettings: () -> Unit,
    showSettings: Boolean,
) {
    val connectionDescription = stringResource(
        when (connection.status) {
            ConnectionUiStatus.Connected -> R.string.top_bar_connection_connected
            ConnectionUiStatus.Scanning -> R.string.top_bar_connection_scanning
            ConnectionUiStatus.Connecting -> R.string.top_bar_connection_connecting
            ConnectionUiStatus.Error -> R.string.top_bar_connection_error
            ConnectionUiStatus.Unavailable -> R.string.top_bar_connection_unavailable
            ConnectionUiStatus.Disconnected -> R.string.top_bar_connection_disconnected
        },
    )
    TopAppBar(
        title = {
            Text(text = title, modifier = Modifier.testTag("page_title"))
        },
        navigationIcon = {
            if (showBack) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("navigate_back")) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.nav_back),
                    )
                }
            }
        },
        actions = {
            IconButton(
                onClick = onOpenDevice,
                modifier = Modifier
                    .testTag("top_bar_connection")
                    .semantics { contentDescription = connectionDescription },
            ) {
                ConnectionIndicator(connection = connection)
            }
            if (showSettings) {
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.testTag("open_settings"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = stringResource(R.string.open_settings),
                    )
                }
            }
        },
        modifier = Modifier.testTag("top_app_bar"),
    )
}

@Composable
private fun FeizhelaNavigationRail(
    selectedRoute: String,
    onSelect: (String) -> Unit,
) {
    NavigationRail(
        modifier = Modifier.testTag("navigation_rail"),
        header = {
            Column(
                modifier = Modifier.padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.app_short_name),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        },
    ) {
        BottomDestination.entries.forEach { destination ->
            NavigationRailItem(
                selected = selectedRoute == destination.route,
                onClick = { onSelect(destination.route) },
                icon = {
                    Icon(imageVector = destination.icon, contentDescription = null)
                },
                label = { Text(stringResource(destination.labelRes)) },
                modifier = Modifier.testTag("nav_${destination.name.lowercase()}"),
            )
        }
    }
}

@Composable
private fun TwoPaneRecords(
    appState: FeizhelaAppState,
    statusFilter: SessionStatusFilter,
    dateFilter: SessionDateFilter,
    onStatusFilterChange: (SessionStatusFilter) -> Unit,
    onDateFilterChange: (SessionDateFilter) -> Unit,
    selectedSessionId: String?,
    onSelect: (String) -> Unit,
    onExport: (String) -> Unit,
    onDelete: (String) -> Unit,
    nowMillis: Long,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        RecordsScreen(
            sessions = appState.sessions,
            statusFilter = statusFilter,
            dateFilter = dateFilter,
            onStatusFilterChange = onStatusFilterChange,
            onDateFilterChange = onDateFilterChange,
            onOpenSession = onSelect,
            selectedSessionId = selectedSessionId,
            nowMillis = nowMillis,
            modifier = Modifier
                .weight(0.40f)
                .fillMaxHeight(),
        )
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Box(
            modifier = Modifier
                .weight(0.60f)
                .fillMaxHeight()
                .testTag("record_detail_pane"),
        ) {
            val detail = selectedSessionId?.let(appState::detailFor)
            if (detail == null) {
                EmptyState(
                    modifier = Modifier.fillMaxSize(),
                    title = stringResource(R.string.records_two_pane_placeholder),
                )
            } else {
                SessionDetailScreen(
                    detail = detail,
                    onExport = onExport,
                    onDelete = {
                        selectedSessionId?.let(onDelete)
                    },
                )
            }
        }
    }
}

@Composable
private fun titleForRoute(route: String): String = when (route) {
    FeizhelaRoutes.TONIGHT -> stringResource(R.string.nav_tonight)
    FeizhelaRoutes.RECORDS -> stringResource(R.string.nav_records)
    FeizhelaRoutes.DIARY -> stringResource(R.string.nav_diary)
    FeizhelaRoutes.DEVICE -> stringResource(R.string.nav_device)
    FeizhelaRoutes.SETTINGS -> stringResource(R.string.settings_title)
    FeizhelaRoutes.RECORD_DETAIL -> stringResource(R.string.title_session_detail)
    else -> stringResource(R.string.app_short_name)
}

private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
