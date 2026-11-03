# 采集链路集成说明（Bluetooth → Room → 频谱 Epoch）

本文说明头环采集链路的分层、所有权、失败语义、持久化、调用方接入方式、测试现状与后续边界。

**编写依据与限制**

- 依据是对仓库现有源码的静态阅读（清单见文末），加上主代理在本机实际执行的构建与测试结果（见 §5）。
- 文中“已执行/通过”仅指 §5 记录的 JVM 单元测试与一台 API 35 设备上的仪器测试；真机蓝牙硬件、厂商省电与长时间后台**未验证**。
- 源码未体现、但需要产品/联调决定的地方标为「待确认」；未实现的能力标为「未实现」，不臆造实现。

---

## 1. 分层与所有权

### 1.1 分层

| 层 | 关键类型与文件 | 职责 | 由谁创建/持有 | 由谁关闭 |
|---|---|---|---|---|
| 平台 GATT | `BluetoothClient`（接口）与 `AndroidBluetoothClient`（`app/src/main/java/com/feizhela/headband/bluetooth/AndroidBluetoothClient.kt`）；事件模型在 `BleModels.kt` | 封装 `BluetoothAdapter`、扫描、`BluetoothGatt`、GATT 操作串行队列；把 Android 回调转换为 `BleEvent`。**不解析设备报文** | `AcquisitionService.onCreate()` 创建 | `FlowBluetoothDataSource.close()` 调用 `client.close()`；`AndroidBluetoothClient.close()` 停止扫描、断开并关闭 GATT、置 `Closed`、清空 listener |
| 回调→流适配 | `BluetoothDataSource`（接口）与 `FlowBluetoothDataSource`（`.../bluetooth/BluetoothDataSource.kt`） | 用**有界** `Channel`（默认 512）把回调转成 `Flow<BleEvent>`；复制通知字节；强制**恰好一个**长期收集者；溢出即关闭自身和底层 client | `AcquisitionService.onCreate()` 创建，包裹 client | `DefaultBluetoothRepository.close()` 调用 `source.close()`；`events` 的 `finally` 也会关闭 |
| 设备层仓库 | `BluetoothRepository`（接口）与 `DefaultBluetoothRepository`（`.../bluetooth/BluetoothRepository.kt`） | 汇总连接状态、已发现设备（最多保留 256 个）、订阅集合；把通知包装为 `RawBlePacket` 并只经 `rawPackets` 发布；`events` 只承载控制事件 | `AcquisitionService.onCreate()` 创建，注入 source 与服务级 `CoroutineScope` | `AcquisitionService.onDestroy()` 中的 `bluetooth.close()`；收集协程完成/取消时也会关闭 |
| 会话编排 | `RecordingSessionRepository`（`.../recording/RecordingSessionRepository.kt`） | 创建会话、连接、等待服务发现与订阅完成、按 UUID 过滤原始包、`32` 条或 `1s` 批写、正常停止/中断/失败状态落库 | `AcquisitionService.onCreate()` 创建，注入 `RecordingDao`、`BluetoothRepository`、服务级 scope | 单次录制由 `stop()`/`interrupt()` 收尾；类本身有 `close()`，但当前 `AcquisitionService` 的销毁路径直接调用 `interrupt("SERVICE_DESTROYED")`，没有调用 `controller.close()`（见 1.3） |
| 后置分析 | `SignalAnalysisRepository`、`SignalEpochAssembler`（`.../recording/`） | 跟随已提交 Room 原始包、解码与计数器组窗、质量拒绝、频谱估计、派生 epoch upsert；不订阅 BLE | 服务创建，注入 `recordingDao / signalEpochDao` | 录制收尾后 `drainFinal()`，服务销毁取消 follower |
| 前台生命周期 | `AcquisitionService`（`.../recording/AcquisitionService.kt`）与内部 `LocalBinder` | 前台服务、通知、启动恢复、串行化启动/停止命令；暴露录制 state、analysisState 与开始/停止入口 | 由 Android 系统创建；`AndroidManifest.xml` 声明 `foregroundServiceType="connectedDevice"`、`exported="false"`、`stopWithTask="false"` | 系统回调 `onDestroy()`；`ACTION_STOP` 可在无新启动时 `stopSelfResult` |
| 持久层 | `RecordingDatabase`、`RecordingDao`、`RecordingSessionEntity`、`RawPacketEntity`（`app/src/main/java/com/feizhela/headband/storage/`） | Room 表、批插、分页读、状态更新、级联删除；`payload` 对存储层不透明 | `AcquisitionService.onCreate()` 中 `RecordingDatabase.open(applicationContext)` 首次打开；进程内单例 | 未显式关闭，随进程生命周期 |

补充：

- `BleModels.kt` 定义 `BleDevice`、`BleConnectionState`、`BleCharacteristic`、`BleWriteType`、`BleEvent`。`BluetoothClient` 的注释明确说明回调线程由实现决定，UI 不应依赖回调线程。
- `AndroidBluetoothClient` 不包含任何 180 字节协议、计数器、采样率或算法代码；它只搬运 `ByteArray`。
- `RecordingSessionRepository` 的注释明确说明：**payload 字节在此层不被解释**。

### 1.2 组装关系

`AcquisitionService.onCreate()` 中的真实组装顺序：

```text
AcquisitionService.onCreate()
├─ TransportProvider.create(applicationContext)                // 正常为 AndroidBluetoothClient；debug 可注入
├─ FlowBluetoothDataSource(client)                             // capacity 默认 512
├─ DefaultBluetoothRepository(source, scope)                   // eventCapacity 默认 512
├─ database = RecordingDatabase.open(applicationContext)       // 进程级 v3 Room 单例
├─ RecordingSessionRepository(database.recordingDao, bluetooth, scope)
├─ SignalAnalysisRepository(database.recordingDao, database.signalEpochDao)
└─ recovery = scope.async {
     val result = controller.recoverInterruptedSessions()
     analysisJob = analysis.follow(scope, controller.state)   // 恢复后跟随已提交数据
     result
   }
```

- `scope` 由 `SupervisorJob + Dispatchers.IO + CoroutineExceptionHandler` 构成；异常处理器记录日志，并在服务未销毁时尝试把通知更新为“采集操作失败，请打开应用查看并重试”。
- `DefaultBluetoothRepository` 在 `init` 中立即启动一个收集 `source.events` 的协程；因此数据源在连接前就已经有唯一消费者。
- `RecordingSessionRepository` 在 `start()` 的 `beginSession(id)` 中才安装 `bluetooth.rawPackets` 的收集协程，且安装发生在 `bluetooth.connect(device)` 之前；这满足 `rawPackets` “连接前必须已有消费者”的约束。
- 扫描/连接/订阅/读写等控制动作只允许经 `BluetoothRepository` 发起，最终委托给 `FlowBluetoothDataSource` 与 `AndroidBluetoothClient`。

### 1.3 关闭责任链

正常销毁时实际执行的顺序：

```text
AcquisitionService.onDestroy()
├─ destroying = true；removeForeground()
└─ scope.launch(NonCancellable) {
     commands.withLock {
       recovery.await()
       controller.interrupt("SERVICE_DESTROYED")   // 刷出已排队批次并写 INTERRUPTED
       drainAnalysisBounded()                     // 最多 5s；失败不改原始录制
       analysisJob?.cancel()
     }
     bluetooth.disconnect()
     bluetooth.close()                             // → source.close() → client.close()
     scope.cancel()
   }
```

- `FlowBluetoothDataSource.close()`：`client.setListener(null)` → `client.close()` → `channel.close()`；用 `AtomicBoolean` 保证幂等。
- `AndroidBluetoothClient.close()`：`stopScan()` → `closeGatt()` → `changeState(Closed)` → 清空 listener；同样幂等。
- `DefaultBluetoothRepository.close()`：`source.close()` → 状态置 `Closed` 并清空设备/服务/订阅 → 取消收集协程；幂等。收集协程正常完成、异常或取消时，`finally` 也会调用 `close()`。
- `AcquisitionService.onDestroy()` 的真实收尾在 `NonCancellable` 协程中异步执行，`super.onDestroy()` 先返回；若进程紧接着被杀，未完成的落盘由下次启动的 `recoverInterruptedSessions()` 兜底（见 2.4）。
- `RecordingSessionRepository` 的 `stop()`/`interrupt()` 只结束“当前这一次录制”，不会关闭数据库或蓝牙仓库；服务可以在同一个前台生命周期内再次 `startRecording`。
- `RecordingSessionRepository.close()` 会异步执行 `interrupt("REPOSITORY_CLOSED")` 再断开蓝牙，但当前服务销毁流程没有调用它；这是现有接线事实，是否需要统一见 7。
- `RecordingDatabase` 是进程级单例，没有显式 `close()`，由进程退出回收。

### 1.4 为什么 UI 不直接碰 GATT 或 Dao

1. **线程模型**：GATT 回调来自 Binder/系统线程，`BluetoothClient` 的注释明确说线程由实现决定。UI 需要主线程安全的状态；`FlowBluetoothDataSource` 负责把回调搬进有界流，仓库再在服务 scope（IO）中消费。UI 直接收回调会引入线程与生命周期竞态。
2. **单消费者契约**：`FlowBluetoothDataSource.events` 用 `AtomicBoolean` 强制“恰好一个收集者”，第二个收集者会抛 `IllegalStateException("BLE events allow exactly one collector")`。如果 UI 也去收集，就会破坏仓库的唯一消费；Activity 重建或退到后台时还会造成消费者消失或流被截断。
3. **字节所有权**：通知在 client、source、`RawBlePacket`、会话队列、写入实体之间逐层复制，订阅者拿到的 `payload` 是副本。UI 直接持有 GATT 缓冲区或原始数组，可能改写到归档数据。
4. **持久化一致性**：会话 id、每会话单调 `sequence`、批事务、`FAILED`/`INTERRUPTED` 收尾必须由单一所有者决定。UI 直接写 Dao 会绕过序号分配、批大小、外键和失败收尾，产生重复主键或半条会话。
5. **生命周期**：采集必须跨 Activity 重建、退后台继续，只有前台服务持有传输与写入才成立。UI 只应观察 `RecordingState`，通过 `LocalBinder` 发起开始/停止，不应持有 `AndroidBluetoothClient`、`BluetoothRepository` 或 `RecordingDao` 的引用。

---

## 2. 失败与中断语义

### 2.1 有界缓冲：溢出即中断并关闭传输

传输/仓库层没有任何“丢一个包继续跑”的分支：

- `FlowBluetoothDataSource`：默认容量 512；listener 内 `trySend` 失败时，`closed` 置位、`channel.close(BleBufferOverflowException())` 并立即 `client.close()`。`BleBufferOverflowException` 的固定消息是 `BLE event buffer exhausted; acquisition interrupted`。此后 `startScan/connect/subscribe/read/write` 返回失败（`IllegalStateException("Bluetooth data source is closed")`），`stopScan/disconnect` 变为 no-op。
- `DefaultBluetoothRepository`：`rawPackets` 与 `events` 都是 `MutableSharedFlow(extraBufferCapacity = eventCapacity)`；通知的 `tryEmit` 失败会抛 `BleBufferOverflowException`，控制事件的 `tryEmit` 失败同样抛该异常。
- 溢出异常被仓库收集协程捕获后，写入 `BluetoothSessionState.lastError = BleEvent.Error("eventStream", ..., cause = error)`，尝试发一条 `events` 错误事件，并在 `finally` 中关闭仓库（`source.close()`、状态 `Closed`、清空连接信息）。
- 源端 `channel.close(cause)` 也会让仓库的收集协程收到异常，因此源端溢出同样会冒泡为仓库的 `eventStream` 错误并关闭。

结论：**容量满被视为数据完整性失败，而不是可恢复的背压**；不会静默丢样本。

### 2.2 无原始包消费者或慢消费者：显式报错

- `rawPackets` 是**无重放**的实时 `SharedFlow`。仓库在每条 `NotificationReceived` 上先执行：
  `check(mutableRawPackets.subscriptionCount.value > 0) { "Raw packet consumer is missing" }`
  没有活跃消费者时，该检查抛异常，随后按 2.1 的路径变成 `lastError`（`operation = "eventStream"`，消息含 “consumer”）并关闭仓库。换句话说：**不允许在无人归档的情况下建立连接**。
- 慢消费者：`rawPackets` 的缓冲满后 `tryEmit` 返回 false，仓库抛 `BleBufferOverflowException` 并关闭；不会阻塞 GATT 回调，也不会丢包后继续。
- 会话层还有一层有界队列：`RecordingSessionRepository` 的 `packetChannel` 容量为 128。写盘慢时 `packets.send(...)` 会挂起包收集协程，上游仓库缓冲被填满，最终触发上述显式溢出。
- `BluetoothRepository` 的接口注释明确：`rawPackets` 的存储消费者必须在连接前安装；缺失或慢消费者会显式失败连接；持久化重放由录制仓库负责，不由该流负责。

### 2.3 写盘失败：转 `FAILED`

写盘失败不是“继续录制但少几条”：

- `writeBatches()` 捕获非取消异常后：`storageFailed = true`、`acceptingPackets = false`、状态置 `RecordingPhase.Failed`（带 `sequence` 与原因）、`bluetooth.disconnect()`、`dao.finishSession(id, "FAILED", now, reason)`、取消包收集协程并取消队列。
- `failActiveStorage()`（包收集协程异常）和 `persistFailure()`（`finishSession` 本身失败）走同一语义：置 `Failed`、断开、尽量写 `FAILED` 与 `endReason`。
- 启动阶段在置 `Recording` 前有 `check(!storageFailed.get())`；启动失败由 `failStartup()` 写 `FAILED` 并返回 `Result.failure`。
- `stop()` 开头检查 `if (storageFailed.get()) return`，因此写盘失败后用户再点停止，不会把 `FAILED` 覆盖成 `COMPLETED`。
- 原因文本取异常的 `message`，为空时退化为异常类简单名。

边界：如果连 `finishSession(..., "FAILED", ...)` 都失败，数据库行会停留在 `ACTIVE`；下次服务启动的恢复逻辑会把它标成 `INTERRUPTED`/`PROCESS_RESTART`。也就是说，极端情况下“内存中的 FAILED”在重启后表现为“进程重启中断”，而不是 FAILED。此行为由代码路径推断，待运行确认。

### 2.4 断连与进程重启

**进程重启（已实现）**

- `AcquisitionService` 返回 `START_NOT_STICKY`，注释明确：进程死亡由持久化恢复，不能假装旧连接还活着。
- `onCreate()` 启动 `recovery = scope.async { controller.recoverInterruptedSessions() }`；每次 `startRecording` / `stopRecording` / `ACTION_STOP` / `onDestroy` 都先 `recovery.await()`，避免恢复逻辑误伤刚创建的新会话。
- `recoverInterruptedSessions()` 调用 `RecordingDao.interruptActiveSessions(now, "PROCESS_RESTART")`，只更新 `status = 'ACTIVE'` 的行，写入 `endedAtMillis` 与 `endReason`；重复执行安全，终态行不会被改写。
- 注意：恢复只改数据库，不重置内存中的 `RecordingState`；因此在没有新命令时会保持默认 `Idle`。

**运行中断连（已实现）**

- `RecordingSessionRepository.start()` 在置 `Recording` 后调用 `startConnectionMonitor()`，用独立协程持续收集 `BluetoothRepository.state`。
- 触发条件：当前 phase 仍为 `Recording`，且出现 `connection == Idle`、`connection == Closed` 或 `lastError != null`。
- 触发后的动作：以 `lastError.message` 为原因（为空时用 `BLE_DISCONNECTED`）派发 `interrupt(reason)`，即停止收包、断开、刷残余批次、写 `INTERRUPTED` 与 `endReason`。
- 避免误判：`stop()`/`interrupt()` 会先把 phase 置 `Stopping` 再 `disconnect()`，因此自身主动断开不会再次触发中断。监视协程在 `stopCollectorsAndFlush()` 与 `clearSession()` 中取消，不泄漏。
- 死锁规避：监视协程不直接在当前栈里调用 `interrupt()`，而是另起协程排队等 `lifecycleMutex`，避免与已持锁的 `start()`/`stop()` 互等。
- 该语义已由 `RecordingSessionRepositoryTest.disconnectInterruptsRecordingAndPersistsItsReason` 实际执行验证（见 §5）。
- 仍是事实的边界：仓库关闭不会 complete `rawPackets`（`MutableSharedFlow` 无关闭信号），但断连/关闭会通过 `state` 触发上述中断路径。

### 2.5 通知不再伪装连接成功

- 通知初始文本是 `服务已就绪，等待选择设备；尚未开始连接或采集`（`READY_TEXT`），明确区分“服务就绪”和“已连接/已采集”。
- `onStartCommand(ACTION_START_FOREGROUND)` 的注释写明：`Foreground readiness says nothing about a GATT connection or subscription.` 该 action 只负责建立前台服务与通知，不建立连接。
- 只有 `RecordingSessionRepository.start()` 真正完成“连上 + 服务发现 + 订阅成功”后，`LocalBinder.startRecording` 才会把通知改为 `采集会话已启动，请在应用查看连接和录制状态`；失败则改为 `未能开始采集，请在应用查看原因并重试`。
- `POST_NOTIFICATIONS` 不是前台服务的前提；代码注释说明即使通知抽屉权限被拒，系统仍可能只在任务管理器展示通知。
- `foreground` 只表示前台服务与通知已建立，**不是** GATT 连接状态。连接/订阅状态必须读 `BluetoothRepository.state`（`BluetoothSessionState`）或 `RecordingState`，不能从通知推断。
- 异步的 `FAILED`（例如写盘失败）不会自动改写通知文本；服务只在命令成功/异常路径更新通知，因此 UI 应以 `RecordingState` 为准。

### 2.6 会话状态机

`RecordingPhase`：`Idle`、`Starting`、`Recording`、`Stopping`、`Completed`、`Interrupted`、`Failed`。

| 起点 | 触发 | 终点 | 持久化 |
|---|---|---|---|
| `Idle`/终态 | `start()` 创建会话行 | `Starting` | 插入 `ACTIVE` |
| `Starting` | 连接、服务发现、订阅均成功 | `Recording` | 仍为 `ACTIVE` |
| `Starting` | 连接/发现/订阅失败或启动超时 | `Failed` | `FAILED` + `endReason` |
| `Recording`/`Starting` | `stop()` | `Stopping` → `Completed` | `COMPLETED`，`endReason = null` |
| `Recording`/`Starting` | `interrupt(reason)` | `Stopping` → `Interrupted` | `INTERRUPTED` + `reason` |
| `Recording` | 写盘失败 | `Failed` | `FAILED` + 原因 |
| 任意进程内 `ACTIVE` | 下次进程启动恢复 | 内存默认 `Idle`；数据库行 `INTERRUPTED` | `PROCESS_RESTART` |

启动等待上限 `START_TIMEOUT_MILLIS = 30_000L`，覆盖连接、服务发现与订阅完成。

### 2.7 失败映射总表

| 触发 | 传输/仓库层行为 | 会话/持久层行为 |
|---|---|---|
| 事件流缓冲溢出 | 关闭 client / 仓库，写 `lastError` | 目标状态：`INTERRUPTED`（经 `lastError` 触发监视路径），需真机复核 |
| 无 `rawPackets` 消费者 | `check` 失败 → `Error("eventStream")` → 关闭 | 同上 |
| 慢消费者 | `BleBufferOverflowException` → 关闭 | 同上 |
| 写盘失败 | 断开蓝牙 | `FAILED` + `endReason` |
| 运行中物理断连 | `StateChanged(Idle)` / `lastError` | `INTERRUPTED` + 原因 |
| 启动失败 | 断开蓝牙 | `FAILED` + `endReason` |
| 正常停止 | 断开蓝牙 | `COMPLETED`，无 `endReason` |
| 显式 `interrupt(reason)` | 断开蓝牙、刷批 | `INTERRUPTED` + `reason` |
| 进程重启 | — | `ACTIVE` → `INTERRUPTED` / `PROCESS_RESTART` |
| 运行中物理断连 | `StateChanged(Idle)` | 监视 `state` 后转 `INTERRUPTED` + 原因 |

---

## 3. 持久化

### 3.1 表结构

数据库文件：`recordings.db`（`RecordingDatabase.open` 固定名称），当前 Room schema 版本 `3`，`exportSchema = true`。v1/v2 的会话、原始包与 gap 经非破坏迁移保留；分析结果另存于 `signal_epochs`，不回写原始包。

**`recording_sessions`**（实体 `RecordingSessionEntity`）

| 列 | 类型/约束 | 说明 |
|---|---|---|
| `id` | `TEXT NOT NULL`，主键 | `UUID.randomUUID().toString()` |
| `deviceAddress` | `TEXT NOT NULL` | 设备地址 |
| `deviceName` | `TEXT NULL` | 扫描得到的名称 |
| `serviceUuid` | `TEXT NOT NULL` | 订阅的服务 UUID 字符串 |
| `characteristicUuid` | `TEXT NOT NULL` | 订阅的特征 UUID 字符串 |
| `startedAtMillis` | `INTEGER NOT NULL` | `System.currentTimeMillis()` |
| `endedAtMillis` | `INTEGER NULL` | 结束/失败/中断时间 |
| `status` | `TEXT NOT NULL` | 默认 `ACTIVE`；另有 `COMPLETED`、`INTERRUPTED`、`FAILED` |
| `endReason` | `TEXT NULL` | 中断/失败原因；正常停止为 `null` |

索引：`startedAtMillis`（列表查询按 `startedAtMillis DESC, id ASC` 排序）。

**`raw_packets`**（实体 `RawPacketEntity`）

| 列 | 类型/约束 | 说明 |
|---|---|---|
| `sessionId` | `TEXT NOT NULL`，复合主键之一 | 外键指向 `recording_sessions.id` |
| `sequence` | `INTEGER NOT NULL`，复合主键之一 | 每会话从 0 开始单调递增，仅对接受的包自增 |
| `receivedAtMillis` | `INTEGER NOT NULL` | 通知回调时刻 `System.currentTimeMillis()` |
| `payload` | `BLOB NOT NULL` | 原始字节；**当前不校验长度、不解析** |

- 主键：`(sessionId, sequence)`；重复序号会失败而不是覆盖归档字节。
- 外键：`sessionId → recording_sessions.id`，`ON DELETE CASCADE`，`ON UPDATE NO ACTION`；删除会话会级联删除包。
- 索引：`(sessionId, receivedAtMillis)`。
- 读取：`readPackets(sessionId, afterSequence, limit)` 以 `sequence` 升序、`afterSequence` 为排他游标分页。
- 会话查询：`observeSessions()` / `observeSession(id)` 返回 `Flow`。

**`acquisition_gaps`**（实体 `AcquisitionGapEntity`）

| 列 | 类型/约束 | 说明 |
|---|---|---|
| `id` | `INTEGER PRIMARY KEY AUTOINCREMENT` | 自增主键 |
| `sessionId` | `TEXT NOT NULL` | 外键指向 `recording_sessions.id`，级联删除 |
| `kind` | `TEXT NOT NULL` | `BUFFER_OVERFLOW` / `DISCONNECT` / `SUBSCRIPTION_LOST` / `SERVICE_RESTART` / `DEVICE_GAP` |
| `startedAtMillis` | `INTEGER NOT NULL` | 首次确认数据流不完整的时刻 |
| `endedAtMillis` | `INTEGER NULL` | 恢复时刻；`null` 表示到会话结束仍未恢复 |
| `droppedEstimate` | `INTEGER NULL` | 估算丢失包数；`null` 表示无法诚实估算 |
| `detail` | `TEXT NULL` | 触发原因文本 |

- 索引：`(sessionId, startedAtMillis)`；读取顺序 `(startedAtMillis ASC, id ASC)`。
- 用途：对应 framework 第 71 行「记录 gaps」与第 189 行「可追溯」。当前由断连/缓冲溢出路径写入，`kind = DISCONNECT` 或 `BUFFER_OVERFLOW`。

### 3.1b `signal_epochs` 与导出契约

- 每行保留 `sessionId / epochIndex / startSampleIndex / startedAtMillis / firstPacketSequence / lastPacketSequence`，用于追溯输入区间；`receivedSamples / expectedSamples` 表示实际接收数与目标覆盖数（默认 3120）。
- `quality / reason` 是覆盖、连续性和运算状态的证据。`rawSamplingRate=104`、`analysisSamplingRate=20`、`algorithmVersion`、原样 `configJson` 记录算法来源；可空的 `componentsJson / analysisWindow / residualRatio / verified` 保存频谱结果。无结果时这些列保持 null，不造零值。
- `SignalEpochDao` 按 epochIndex 升序读取/观察，upsert 当前 `(sessionId, epochIndex)`；算法版本是行级 provenance，并非多版本联合主键。重放/新版本分析可能替换同一 epoch 的派生行，原始包仍是重建依据。
- `RecordingLibrary(dao, epochDao = null)` 保持原单参数调用有效；注入后 `observeEpochs(sessionId)` 返回 Room 实时 flow，未注入则发出空列表。删除会话经数据库外键级联删除 raw/gap/epoch。
- `RecordingExporter(dao, epochDao = null)` 的 CSV 仍是 schema 1、原样小写 hex。summary JSON 升为 schema 2，新增 `epochs`（保留全部实体字段及原样 config/components JSON 字符串）、`epochCount / analyzedEpochCount / qualityCounts`、`analysisKind="spectral-only"`；`analysisStoreAvailable` 只表示 DAO 已注入，`analysisAvailable` 只表示已有非空 componentsJson，不能表示生理质量或分析已全部完成。未注入、尚无 epoch、仅拒绝/partial 证据三种情况可据此区分。
- summary 的所有读取/序列化在首次写 sink 之前完成，读失败不输出部分文件；活动会话的 session/gap/epoch 读取不在同一事务中，导出不承诺原子快照。summary 内存随 epoch 数增长，CSV 继续按包分页。

### 3.2 批写

- 常量：`BATCH_SIZE = 32`、`FLUSH_INTERVAL_MILLIS = 1_000L`、`PACKET_CHANNEL_CAPACITY = 128`。
- `beginSession()` 先安装 `rawPackets` 收集器，再在 `writeBatches()` 中消费队列。
- 批量策略：空批时阻塞等第一条；非空时最多等 1 秒再收下一条；满 32 条或等待超时即 `flush`；`stop()`/`interrupt()` 会先取消收集器、`join`、关闭队列，再由 writer 刷出残余批次。
- `flush()` 调 `dao.insertPackets(batch.toList())`；`@Insert(onConflict = ABORT)` 对列表在一个事务中执行，重复主键或未知 `sessionId` 会使整批回滚（仪器测试覆盖了该意图）。
- 每批成功后才增加 `RecordingState.packetCount`；`sequence` 在包进入队列时分配。
- 仅接受 `acceptingPackets == true` 且 `serviceUuid`、`characteristicUuid` 字符串都匹配的包；其他包被忽略。
- 启动窗口：`acceptingPackets` 在调用 `bluetooth.subscribe(...)` **之前**置 `true`。原因是首个通知可能在 CCCD 写成功与本端收到 `SubscriptionCompleted` 之间到达；若等确认后再置位，每个会话开头都会静默丢样本。若订阅最终失败，会话按 `FAILED` 收尾，已收包仍归属该失败会话，不跨会话串数据（队列与收集器均按会话重建并带 UUID 过滤）。

### 3.2b 写入积压与磁盘指标

`RecordingMetrics` 由 `RecordingState.metrics` 暴露，字段：`queuedPackets`、`maxQueuedPackets`、`writtenPackets`、`oldestQueuedAgeMillis`、`lastBatchWriteMillis`、`maxBatchWriteMillis`、`databaseBytes`、`gapCount`、`droppedEstimate`。

- 采集路径只用原子计数（不按包发布状态）；`flush()` 每批一次更新：`System.nanoTime` 测本批写入耗时、成功写入后累加 `writtenPackets`、按入队时间算最老待写包等待时长、快照积压深度。
- `gapCount`/`droppedEstimate` 以 ≥1 秒节流刷新，`stop()`/`interrupt()` 收尾时强制刷新。
- **`databaseBytes` 目前恒为 0**：`RecordingSessionRepository` 的构造参数没有 `Context`/数据库路径，DAO 也没有 size 查询，因此不伪造该值。补齐方式是在构造处注入一个 `() -> Long` 尺寸提供者（`AcquisitionService` 有 `applicationContext`），契约无需改动。

### 3.3 Schema 导出

- `app/build.gradle.kts`：`ksp { arg("room.schemaLocation", "$projectDir/schemas") }`。
- `RecordingDatabase` 注解：`exportSchema = true`，**`version = 3`**。
- 导出文件：`app/schemas/.../1.json`、`.../2.json`、`.../3.json`。
- 迁移：`RecordingMigrations.kt` 中 `MIGRATION_1_2` 创建 `acquisition_gaps` 与索引，追加四个可空 provenance 列；`MIGRATION_2_3` 创建 `signal_epochs`、复合主键 `(sessionId, epochIndex)`、`(sessionId, startSampleIndex)` 索引及会话级联删除外键。builder 注册 `.addMigrations(MIGRATION_1_2, MIGRATION_2_3)`，没有破坏性回退。既有 raw payload 不重写。
- 现有 `RecordingMigrationTest` 从真实 v1 SQLite 文件打开到 v3，注册两段迁移，并检查磁盘版本 3、epoch 列/主键/外键/索引；Room 同时按编译后的 v3 实体校验完整 schema。主代理已在 MuMu 验证当前迁移测试 4/4 通过（见 §5）；本 scoped 子任务未自行运行 Gradle。
- `app/schemas` 已通过 `sourceSets["androidTest"].assets.srcDir(...)` 暴露给仪器测试，后续可直接用 `MigrationTestHelper` 校验 JSON。

### 3.4 自动备份与设备迁移排除

`AndroidManifest.xml` 中：

```xml
android:allowBackup="true"
android:dataExtractionRules="@xml/data_extraction_rules"
android:fullBackupContent="@xml/backup_rules"
```

- `app/src/main/res/xml/backup_rules.xml`：`<full-backup-content>` 排除 `database` 域的 `recordings.db`、`recordings.db-wal`、`recordings.db-shm`、`recordings.db-journal`。
- `app/src/main/res/xml/data_extraction_rules.xml`：在 `cloud-backup` 与 `device-transfer` 两个段中同样排除上述四个文件。
- 目的：生理原始录制不进自动云备份、也不随设备迁移复制。注意 `allowBackup` 仍为 `true`，排除范围仅限数据库文件；其他应用数据是否可备份不在本文范围。

### 3.5 已知限制

- 数据库为普通 Room/SQLite，未加密（见 6）。
- `receivedAtMillis` 与 `startedAtMillis`/`endedAtMillis` 都是墙上时钟，可能受系统改时影响；设备时间戳/采样时钟映射尚未实现（见 6）。
- 归档写入层不做 payload 长度校验；任何字节长度原样落库。后置解码严格要求 180 字节，失败只影响派生分析证据，不改写/删除原始输入。
- CSV/summary 导出核心与 `RecordingLibrary` 已实现；SAF、导出与删除页面是否接入由 UI 负责。
- schema 已提供 v1→v2→v3 非破坏迁移；加密与时钟校准仍待补齐。

---

## 4. 调用方接入方式

### 4.1 前置条件

权限与前台资格由**调用方**负责；`AndroidBluetoothClient` 只做检查，不弹权限框，缺权限时抛 `SecurityException`。

| 事项 | 要求/说明 |
|---|---|
| 扫描 | Android 12+：`BLUETOOTH_SCAN`；Android 11 及以下：`ACCESS_FINE_LOCATION`（client 的 `ensureScanPermission`）。Manifest 已声明，`ACCESS_FINE_LOCATION` 限制 `maxSdkVersion=30` |
| 连接/订阅/读写 | Android 12+：`BLUETOOTH_CONNECT`；更低版本跳过该检查 |
| 前台服务 | `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE`；服务类型为 `connectedDevice`。Android 12+ 从后台启动前台服务受限，调用方必须在有资格的时机调用 |
| 通知 | `POST_NOTIFICATIONS`；未授予不影响 `startForeground`，但通知可能只在任务管理器可见 |
| 蓝牙开关/适配器 | 适配器不可用时 `AndroidBluetoothClient` 返回明确错误（“Bluetooth LE scanner is unavailable...” / “Bluetooth adapter is unavailable”） |
| 设备筛选 | 扫描可传 `serviceUuids` 过滤；连接使用 `BleDevice(address, name, rssi)` |

### 4.2 启动流程

1. 调用方申请并确认蓝牙相关运行时权限。
2. 在允许启动前台服务的上下文中调用 `AcquisitionService.startForeground(context)`。这是伴生方法，内部用 `startForegroundService`（O+）或 `startService` 发送 `ACTION_START_FOREGROUND`，由 `onStartCommand` 执行 `ensureForeground()`，建立 `connectedDevice` 类型的前台通知。
3. `bindService(Intent(context, AcquisitionService::class.java), connection, ...)`，在 `onServiceConnected` 中把 `IBinder` 转为 `AcquisitionService.LocalBinder`。
4. 调用 `LocalBinder.startRecording(device, serviceUuid, characteristicUuid)`。它会：
   - 同步 `check(!destroying && !stopping)`；
   - 再次调用伴生 `startForeground(this@AcquisitionService)`，让“仅绑定”的调用方也获得 started service 生命周期；
   - 在服务协程中经 `commands` 互斥锁串行执行，先 `recovery.await()`，再更新通知“正在连接设备并准备采集，请稍候”；
   - 调用 `controller.start(...)`，成功后通知“采集会话已启动，请在应用查看连接和录制状态”，失败则提示未能开始。
5. 通过 `LocalBinder.state` 观察 `RecordingState`（阶段、sessionId、packetCount、reason），通过 `LocalBinder.analysisState` 观察后置分析状态/错误。`startRecording` 返回的是 `Job`，不是 `Result`；录制成功/失败以 recording state 和通知为准，分析失败不改写录制结果。

以下为底层接入示意（片段本身未独立编译/运行）。仓库已有 `AcquisitionServiceConnection` 与 `AcquisitionViewModel` 适配入口；当前产品演示屏幕尚未接入真实采集：

```kotlin
private var binder: AcquisitionService.LocalBinder? = null

private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        binder = service as? AcquisitionService.LocalBinder
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        binder = null
    }
}

fun connectToAcquisition(context: Context) {
    // 前提：已取得蓝牙运行时权限，且当前允许启动前台服务
    AcquisitionService.startForeground(context)
    context.bindService(
        Intent(context, AcquisitionService::class.java),
        connection,
        Context.BIND_AUTO_CREATE,
    )
}

fun startRecording(device: BleDevice, serviceUuid: UUID, characteristicUuid: UUID) {
    val current = binder ?: error("AcquisitionService is not bound")
    current.startRecording(device, serviceUuid, characteristicUuid)
}
```

- `LocalBinder.state` 的静态类型是 `StateFlow<*>`，只读观察；要读取 `RecordingState` 需显式转换（例如 `as StateFlow<RecordingState>`）。是否后续收紧类型见 7。
- 调用方不应构造 `AndroidBluetoothClient`、`FlowBluetoothDataSource`、`DefaultBluetoothRepository` 或 `RecordingDao`；这些由服务独占。

### 4.3 停止流程

两种入口，语义略有不同：

- **通知按钮**：通知 action `停止采集` 通过 `PendingIntent.getService(..., ACTION_STOP)` 进入 `onStartCommand`。服务置 `stopping = true`，在 `commands` 锁内 `recovery.await()` 后 `controller.stop()`；失败时尝试 `controller.interrupt("STOP_FAILED")`。`finally` 中若没有更新一代的启动，则移除前台并 `stopSelfResult(startId)`。
- **`LocalBinder.stopRecording()`**：同样走 `commands` 锁与 `controller.stop()`，更新通知“采集已停止，服务待命”，但**保留前台服务**，可再次开始下一次录制。
- 另有伴生 `AcquisitionService.stop(context)`，通过 `startService(ACTION_STOP)` 触发同一停止路径。
- 正常停止会刷出残余批次并把会话写成 `COMPLETED`（`endReason = null`）；写盘已失败时不会覆盖 `FAILED`。

### 4.4 接入约束与现状

- `startForeground(context)` 必须在有前台启动资格时调用；Android 12+ 后台启动限制由调用方处理。
- 绑定只是取得控制入口；即使调用方只 bind，`startRecording` 内部的 `startForeground` 也会把服务变为 started，录制不会因解绑 Activity 而结束。
- 调用方应在自身销毁时 `unbindService`；已 started 的服务仍会继续运行。
- 当前 `FeizhelaApp` 已有自适应导航及今晚、设备、记录、详情、日记、设置等演示屏幕；这些屏幕使用 demo app state，尚未接入真实 `AcquisitionService`、Room 库或 epoch flow。`AcquisitionServiceConnection / AcquisitionViewModel` 是已有采集适配入口，产品屏幕仍需接线。
- 详情/设置已有导出提示和演示删除等 UI 流程；当前操作演示状态，真实 `RecordingExporter / RecordingLibrary` 与 SAF 文件选择器尚未连接。

---

## 5. 测试现状

**本节区分三件事：测试文件已编写、已在本机实际执行、执行结果。以下执行结果由主代理在 2026-11-03 实测记录。**

**当前已核实的设备结果（主代理执行）：**

- 最终 Gradle `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` 构建成功；14 个 JVM 测试类共 **110 项通过，0 失败**，包含 43 项信号 API/算法回归、12 项解码/计数器测试和 9 项窗口边界测试。
- MuMu（API 35）选定 8 个采集/存储相关测试类执行完成：`OK (38 tests)`，0 失败。
- 分项：`SignalAnalysisIntegrationTest` 5、`SignalAnalysisRecoveryTest` 2、`SignalEpochStorageTest` 6、`RecordingMigrationTest` 4、`RecordingExportTest` 9、`AcquisitionServiceTest` 2、`SimulatedAcquisitionTest` 4、`RecordingDatabaseTest` 5；手动场景入口因 assumption 跳过，不算作已运行的真机场景。
- 此为选定仪器测试类结果，不代表全部当前仪器测试或 UI 导航测试通过。实际前台服务使用模拟传输的测试已验证录制、异步分析及停止后的完整尾窗；分析与刷盘各有自己的完成时机。

| 测试文件 | 类型 | 覆盖意图（按代码阅读） | 状态 |
|---|---|---|---|
| `app/src/test/java/com/feizhela/headband/bluetooth/BluetoothDataSourceTest.kt` | JVM 单元测试 | client 复用缓冲区时通知字节隔离；容量为 2 时溢出关闭 client 且后续操作失败；关闭幂等且 flow 结束；`subscribe` 返回 client 的 requestId 并转发完成事件。使用私有 `FakeBluetoothClient` | 已执行，4/4 通过 |
| `app/src/test/java/com/feizhela/headband/bluetooth/BluetoothRepositoryTest.kt` | JVM 单元测试 | 订阅完成后先更新 state 再广播；无 rawPackets 消费者时报错并关闭；owner scope 取消/未首次调度即取消也会释放 source；显式 close 幂等；容量为 1 的慢消费者触发 `BleBufferOverflowException`；source 正常完成时无错误关闭；`RawBlePacket` 字节副本与多订阅者互不影响。使用私有 `FakeBluetoothDataSource` | 已执行，9/9 通过 |
| `app/src/test/java/com/feizhela/headband/recording/RecordingSessionRepositoryTest.kt` | JVM 单元测试 | start 等待匹配的订阅完成；订阅同步完成也能捕获；**传输尚未响应时不得把初始 Idle 当成断连**；33 个包按 32 批写、stop 刷残余 1 条；连接失败写 `FAILED`；单包写失败写 `FAILED` 且 stop 不覆盖；满批写失败停止录制；断连转 `INTERRUPTED` 并持久化原因；`interrupt` 刷包并保留原因；恢复只更新 `ACTIVE`。使用私有 `FakeRecordingDao`、`FakeBluetoothRepository` | 已执行，10/10 通过 |
| `app/src/androidTest/java/com/feizhela/headband/storage/RecordingDatabaseTest.kt` | Android 仪器测试（内存 Room） | 批次读取有序、分页、按会话隔离；重复序号回滚整批；删除会话级联、孤儿外键被拒；恢复只改 `ACTIVE` 且不改写终态元数据；会话 Flow 的稳定“新到旧”排序 | 设备实测，5/5 通过 |
| `app/src/test/java/com/feizhela/headband/BluetoothClientContractTest.kt` | JVM 单元测试 | `NotificationReceived` 携带原始字节与来源 UUID；`WriteCompleted` 区分成功/状态/消息；连接状态与写入模式枚举值稳定 | 已执行，3/3 通过 |
| `app/src/androidTest/java/com/feizhela/headband/ComposeNavigationTest.kt` | Android 仪器测试 | UI 导航测试；本次选定采集/存储验证未包含当前 UI 全套 | 当前结果待确认，不沿用旧界面通过记录 |
| `app/src/test/java/com/feizhela/headband/permission/BluetoothPermissionsTest.kt` | JVM 单元测试 | sdk 24/30/31/33/35 的扫描/连接/合并权限集合；`missingPermissions` 在部分/全部/未授予下的稳定顺序 | 已执行，9/9 通过 |
| `app/src/test/java/com/feizhela/headband/recording/SimulatedStreamTest.kt` | JVM 单元测试 | 3000 包 @104 Hz 连续归档、`sequence` 0..2999 连续、stop 刷出 24 条残余批、`COMPLETED`；异 UUID 包被丢弃且不占序号 | 已执行，2/2 通过 |
| `app/src/test/java/com/feizhela/headband/ui/acquisition/AcquisitionViewModelTest.kt` | JVM 单元测试 | phase 映射、断连归零、动作参数透传、设备列表/scanning 推导、失败原因进 `lastError`、动作异常不外抛 | 已执行，8/8 通过 |
| `app/src/androidTest/java/com/feizhela/headband/storage/RecordingMigrationTest.kt` | Android 仪器测试（真实 SQLite 文件） | 当前为 v1→v2→v3 保留原始行/元数据并校验磁盘版本 3、epoch 列/外键/索引；原有 gap 排序/级联/FK 测试保留 | 当前 MuMu 实测 4/4 通过 |
| `app/src/androidTest/java/com/feizhela/headband/storage/RecordingExportTest.kt` | Android 仪器测试（内存 Room） | 原有 CSV/summary/删除覆盖；新增 epoch 全字段、排序、null、quality/counts、availability、epoch 读失败零输出、library flow/级联与 raw 不改写 | 当前 MuMu 实测 9/9 通过 |
| `app/src/androidTest/java/com/feizhela/headband/recording/AcquisitionServiceTest.kt` | Android 仪器测试 | 前台服务可启动并绑定、初始 `Idle`；预先写入的 `ACTIVE` 会话在服务启动后被标为 `INTERRUPTED/PROCESS_RESTART` | 设备实测，2/2 通过 |
| `app/src/androidTest/java/com/feizhela/headband/simulation/SimulatedAcquisitionTest.kt` | Android 仪器测试（模拟头环） | healthy 3 秒写满连续 Room 行且每包 180B；中止/订阅失败/计数器回卷；manual 场景由 assumption 控制 | 当前 MuMu 自动 4/4 通过；manual 跳过 |
| `app/src/androidTest/java/com/feizhela/headband/storage/SignalEpochStorageTest.kt` | Android 仪器测试（真实 Room/SQLite） | epoch 往返、upsert/排序/flow、级联与迁移 | 当前 MuMu 6/6 通过 |
| `app/src/androidTest/java/com/feizhela/headband/simulation/SignalAnalysisIntegrationTest.kt` | Android 仪器测试（模拟输入 + 真实 Room） | 频谱证据、重放幂等、坏输入拒绝、估计异常、估计阻塞时归档/停止、真实服务绑定采集及异步尾窗 | 当前 MuMu 5/5 通过 |
| `app/src/androidTest/java/com/feizhela/headband/simulation/SignalAnalysisRecoveryTest.kt` | Android 仪器测试（真实 Room） | 部分 epoch 写入中断后重建剩余完整窗与尾窗；flat/ADC rail 在估计前拒绝，保留原始输入 | 当前 MuMu 2/2 通过 |
| `app/src/test/java/com/feizhela/headband/recording/SignalEpochAssemblerTest.kt` | JVM 单元测试 | 回卷、重复、reset、坏包、跨多窗缺样、包内跨窗、尾窗、接收时间歧义和仅坏包时间证据 | 已执行，9/9 通过 |

说明：

- **本次最终结果：JVM 110 项通过；选定 MuMu 仪器测试 38 项通过；debug 应用和 androidTest APK 构建成功。**
- `AndroidBluetoothClient` 仍无专门的仪器测试（需要真实 GATT 栈或 Robolectric）；`AcquisitionService` 已有启动/绑定/恢复测试，但通知外观、权限撤销与厂商后台策略仍需真机。
- 模拟链路（§8）在真机上补齐了 Unit 测试无法覆盖的时序问题，并因此发现并修复了一个真实缺陷，见 §8.3。
- 上述结果是本机 JVM 与一台 API 35 设备（`127.0.0.1:16384`）的实测记录；未覆盖真机蓝牙硬件、厂商省电策略与整夜后台。

---

## 6. 未完成 / 不在本次范围

以下列出当前实现的剩余边界。派生分析应跟随已提交的 Room 归档（见 §9），不得另抢 BLE 存储消费权或塞回传输层；`BluetoothClient / AndroidBluetoothClient / FlowBluetoothDataSource` 保持报文不透明。

| 事项 | 现状（源码事实） | 应接入位置 |
|---|---|---|
| 固件解码与物理量标定 | 已有严格 180B 解码与 unsigned wire 值；真实固件一致性、EOG 电压/极性、IMU 有符号及量程换算未校准 | 保持后置解码；以真机协议与标定数据确认，不推断单位 |
| 计数器与时间戳 | 已处理 modulo 回卷、缺口、duplicate、reset/歧义分段；墙上时钟只提供接收锚定估计，不能给出可信设备绝对时间 | 补设备时钟同步、漂移与系统改时验证 |
| OK 生理质量与 30 秒分析 | 已有 3120 样本窗口、连续性/flat/ADC rail 门和三分量频谱；生理 OK 门、运动伪迹、信号通路校准仍未完成 | 后置质量层用硬件与标注样本验证；不能把 SPECTRAL_ONLY 当 OK |
| Epoch 与分类 | v3 已存版本化频谱证据和拒绝原因；尚无 framework 的完整候选、特征、概率/置信度、睡眠分类链 | 后续独立算法层；需明确版本和验证依据，不由现有谱分量推断临床状态 |
| SAF / 真实存储接线 | 已有演示导出/删除 UI 流程及经仪器测试的 CSV/summary/library 核心；演示状态尚未连接真实归档/SAF 文件选择器 | UI 层把 `OutputStream` 交给 `RecordingExporter`；删除必须走 `RecordingLibrary`，不得绕过会话与序号语义 |
| `databaseBytes` 指标 | 恒为 0，构造参数缺少 Context/数据库路径 | 在 `RecordingSessionRepository` 构造处注入 `() -> Long` 尺寸提供者 |
| 数据库加密 | Room 默认 SQLite（`recordings.db`），无 SQLCipher/加密依赖 | 存储层；会影响 `RecordingDatabase.open`、备份策略与迁移，待产品与安全确认 |
| 真机后台与厂商省电验证 | 仓库内无真机验证记录；服务 `START_NOT_STICKY`；Manifest 声明了 `WAKE_LOCK`，但主源码没有 `PowerManager`/wake lock 使用 | 需在目标系统/厂商机型验证；`framework.md:82` 明确要求，不能用前台服务代替结论 |
| 采集 UI 接入 | `FeizhelaApp` 已有产品演示屏幕/导航；`AcquisitionViewModel` 与 `AcquisitionServiceConnection` 已实现，演示屏幕尚未接入真实采集 | UI 层只经 ViewModel 与 `LocalBinder` 观察/发动作，页面本身本次按约定不改 |

---

## 7. 待确认汇总

1. ~~**断连自动中断**~~：已在 `RecordingSessionRepository` 中接线，经 `startConnectionMonitor()` 观察 `state` 并转 `interrupt(reason)`，已有测试通过（见 §2.4、§5）。
2. ~~**启动窗口丢包**~~：已在 `subscribe()` 之前置 `acceptingPackets = true`（见 §3.2），意图是消除会话开头的静默丢样本；该时序的真机行为仍建议实测确认。
3. **时间基准**：`receivedAtMillis` 是墙上时钟；设备时间戳/采样时钟映射、时钟跳变处理尚未定义。
4. **FAILED 写不进去**：`finishSession` 自身失败时行保持 `ACTIVE`，下次启动会变成 `INTERRUPTED/PROCESS_RESTART`；这种降级是否可接受。
5. **Binder 类型**：`LocalBinder.state` 暴露为 `StateFlow<*>`，调用方必须显式转换；是否收紧为 `StateFlow<RecordingState>`。
6. **控制器关闭路径**：`RecordingSessionRepository.close()` 存在，但服务销毁走 `interrupt("SERVICE_DESTROYED")`；两者是否需要统一。
7. **加密与 SAF**：schema 已有 v1→v2→v3 迁移；数据库加密、SAF 选择器、导出界面的产品与合规要求未定。
8. ~~**测试执行**~~：已在 JVM 与一台 API 35 设备上执行，结果见 §5；真机蓝牙硬件与后台时长仍未验证。

---

## 8. 模拟数据（MuMu / 无硬件环境）

### 8.1 结构

模拟实现全部位于 `app/src/debug/`，release 变体不参与编译：

| 文件 | 作用 |
|---|---|
| `simulation/FirmwarePacketSimulator.kt` | 按固件基线生成 180 B = 10 × 18 B，每样本 9 个 LE `uint16`（counter、EOG、pulse、accelXYZ、gyroXYZ）；counter 16 位逐样本 +1 并回卷；104 Hz ÷ 10 样本/包 ≈ 10.4 包/秒 |
| `simulation/SimulatedBluetoothClient.kt` | 不触碰 Android BLE 的 `BluetoothClient` 实现：Scanning → DeviceFound → Connecting → Connected → ServicesDiscovered → 订阅完成 → 通知；支持断链、丢包、卡死、订阅失败 |
| `simulation/SimulationScenarios.kt` | 场景目录与 `byName()` |
| `simulation/SimulationRun.kt` | 组装真实链路：模拟 client → `FlowBluetoothDataSource` → `DefaultBluetoothRepository` → `RecordingSessionRepository` → 真实 Room |

注入缝：[TransportProvider.kt](</R:/project/feizhela/app/src/main/java/com/feizhela/headband/bluetooth/TransportProvider.kt>) 用 `BuildConfig.DEBUG` 守卫，`AcquisitionService.onCreate()` 只改一行取客户端；release 的 `DEBUG=false` 时忽略工厂并回退真实实现。

### 8.2 运行方式

```powershell
adb connect 127.0.0.1:7555          # MuMu 12 默认端口，以本机设置为准
.\gradlew.bat :app:installDebug :app:installDebugAndroidTest

# 全部模拟测试
adb shell am instrument -w -e class com.feizhela.headband.simulation.SimulatedAcquisitionTest `
  com.feizhela.headband.test/androidx.test.runner.AndroidJUnitRunner

# 手动指定场景
adb shell am instrument -w -e class "com.feizhela.headband.simulation.SimulatedAcquisitionTest#manualScenarioFromInstrumentationArguments" `
  -e scenario disconnectMidSession -e durationMillis 5000 -e keepSession true `
  com.feizhela.headband.test/androidx.test.runner.AndroidJUnitRunner
```

场景：`healthy`、`droppedPackets`、`disconnectMidSession`、`slowStorage`、`counterWrap`、`subscriptionFailure`、`stalledLink`。

注意：模拟目前通过仪器测试进程驱动，`SimulationRun` 自己安装工厂；正常启动 App 时工厂为空，仍走真实 BLE。若要让 Service 在手工启动时也用模拟，需要一个 debug-only 入口在服务创建前安装工厂（尚未实现）。

### 8.3 模拟验证发现并修复的真实缺陷

`RecordingSessionRepository.start()` 原本用
`state.first { servicesDiscovered || lastError != null || Idle || Closed }`
等待连接。但仓库初始状态就是 `Idle`，而真实传输的 `Connecting/Connected` 事件是**异步**经通道归约的——这个等待会抢在 `connect()` 的事件到达前命中初始 `Idle`，把正常连接误判为「服务发现前已断开」，使真实设备上的录制几乎必然失败。

修复两处：

1. `DefaultBluetoothRepository.connect()` 在接受命令后**同步**把状态置为 `Connecting`（并在失败时置 `Idle` + `lastError`），不再让调用方看到过期的 `Idle`。
2. `RecordingSessionRepository.start()` 改为两段等待：先等传输真正响应（`connection != Idle` 或出现 `lastError`），再等 `servicesDiscovered`，避免依赖实现细节。

新增回归测试 `startIgnoresPreConnectIdleUntilTheTransportReacts`：fake 仓库只在测试驱动时才把状态推进到 `Connecting/Connected`，修复前该用例失败。

这个缺陷此前无法被单元测试发现，因为测试 fake 在 `connect()` 里同步把状态设成了 `Connected`。

---

## 9. 已提交归档 → 频谱 Epoch（当前实现）

### 9.1 后置 follower 与算法边界

```text
BLE → 唯一归档消费者 → Room raw_packets 批事务提交
                          ↓ SignalAnalysisRepository 按 sequence 分页跟随已提交行
FirmwarePacketDecoder（恰好 180B，10 × 18B，9 个小端 uint16/样本）
                          ↓ SampleCounterTracker 设备 counter 时间线
SignalEpochAssembler（每窗 3120 个设备采样位置，104Hz 下约 30s）
                          ↓ 仅完整、连续且质量门通过的 EOG raw ADC 值
每 epoch 去均值 → Analyse.estimateFromRaw(centered, 104.0, 20.0, 3)
                          ↓ 低通抗混叠 FIR → Lanczos 插值至 20Hz → calendar 频谱估计
Room signal_epochs（版本 + 配置 + 质量/原因 + 可空频谱证据）
                          ↓ RecordingLibrary.observeEpochs / summary JSON
```

`SignalAnalysisRepository.follow` 用录制状态与 Room 会话 flow 的合并唤醒跟随持久输入，读取在原始批次提交之后发生；丢失一次内存唤醒不丢失归档字节。分析是串行归属，CPU 工作运行在 `Dispatchers.Default`；算法/派生存储错误通过 analysis state 暴露，不能把已经成功的原始录制改成失败。终态重放与 `drainFinal` 从归档补齐尾部窗口；当前实现每个 repository owner 会重放所有终态会话一次，以 upsert 修复崩溃留下的部分派生结果，不以 `pendingSessionIds` 作为完成性判断。进程重建能以相同原始输入重新形成派生证据；本轮 8 小时数据量 MuMu 加速验证中，终态全量重放约 29 秒，证实大量历史归档的重算成本明显。详细规模、耗时和测试边界见 [长记录验证](overnight-validation.md) 与 [机器报告](overnight-validation-result.json)。持久完成标记/调度策略仍需后续设计。服务 lifecycle 的创建、follow、最终 drain 与取消接线需由服务所有者保持一致；`LocalBinder.analysisState` 可观察派生处理进度和错误。

`FirmwarePacketDecoder` 只解码字段，不从 unsigned wire 值臆造 SI 单位；当前分析只用 EOG 通道。窗口先减去该窗均值，避免较大的 ADC 直流偏置占据谱分量。`Analyse.estimateFromRaw` 负责 104→20Hz 抗混叠 FIR 与 Lanczos 重采样，再估计 3 个 tone；其 `analysisWindow` 是估计器选取的分析长度，不承诺等于全部重采样点数。每个 tone 保存 amplitude（raw ADC counts）、frequencyHz 与 phaseRadians，并保存 residualRatio 和估计器 verified 标志。

### 9.2 连续性与拒绝证据

- 窗口按设备 counter 解卷后的采样位置推进，不用 `packet.sequence * 10` 冒充设备时间线。正常 `65535→0` 回卷连续；modulo delta 2..32767 为缺样，缺失位置占据窗口长度但不补零/插值为实测值。
- duplicate（delta 0）不重复加入样本并使相关窗带 `DUPLICATE_COUNTER`；gap 带 `MISSING_DEVICE_SAMPLES`。delta ≥32768 仅能说明倒退或歧义 reset，不能证明 reset 或诚实推算丢失数；当前窗收尾并分段，重启段也保留拒绝原因。
- 非 180B 报文保留 raw source，分析标记 `MALFORMED_PACKET`。partial 尾窗、gap、duplicate、reset/倒退/歧义时间窗不会送入频谱估计；以 `INCOMPLETE` / `REJECTED` 和具体 reason 保存，结果字段为 null。
- 接收时刻倒退或跨度达到半 counter 周期会引发保守的 `AMBIGUOUS_RECEIVE_TIME` 分段。`startedAtMillis` 由通知接收墙上时钟锚定加 104Hz 相对位置估算，包含批包传输延迟和未知时钟漂移；它不是设备测得的准确 epoch 开始时间。原始接收时间与 counter 无法独立排除所有 reset/大缺口。
- 完整窗口还检查 flat energy 与 ADC 端点（0/65535）；运算异常保留 `ANALYSIS_ERROR`。这些门只覆盖已定义的输入完整性/数值问题，不足以验证生理信号、运动伪迹或硬件饱和模型。

### 9.3 证据含义与剩余 framework 缺口

当前算法版本与完整配置字符串随 epoch 保存，成功结果标记 `SPECTRAL_ONLY`，reason 明确为 `PHYSIOLOGICAL_QUALITY_UNCALIBRATED`。频谱 residual/verified 是数值拟合证据；它们不证明信号生理有效，不产生临床判断或睡眠分期，也不能作为睡眠质量、疾病风险或安全建议。summary 中 `analysisAvailable=true` 同样只表示存在派生谱结果。

尚需补齐真实硬件协议/采样率与单位校准、EOG/脉搏/IMU 联合生理质量和运动伪迹门、可信设备时钟/漂移、framework 所需的后续特征/候选与经标注验证的分类模型、整夜性能/后台/恢复及用户展示。当前实现不把这些空缺伪装成已完成分类。分析不修改 `raw_packets.payload` 或 packet sequence，CSV 仍导出原始字节，可用未来算法重放复核。

### 9.4 本次 scoped 修改的验证状态

分析接入阶段由主代理串行执行 Gradle 并安装 APK 到 MuMu：JVM 110 项、选定仪器测试 38 项全部通过，详见 §5。覆盖包含数值黄金回归、窗口边界、服务真实组装、分析阻塞隔离、部分派生结果写入后的重放修复，以及谱证据导出、library flow、删除级联和非破坏迁移。

后续可靠性验证新增 `OvernightArchiveTest` 和 `AcquisitionServiceRecoveryTest`：8 小时数据量加速测试单独执行 `OK (1 test)`；服务销毁/重建与相关分析回归四个 class 执行 `OK (10 tests)`；最终构建成功，JVM 仍为 110 项全部通过。详细实测、复现命令与边界见 [长记录验证](overnight-validation.md)。这些 invocation 包含重叠用例，不能直接相加为独立测试总数。该结果不等于全部当前 UI 测试通过，也不替代实际头环硬件与真实墙钟整夜验证。

---

## 附录：实际读过的文件

主源码与配置：

1. `app/src/main/java/com/feizhela/headband/bluetooth/BleModels.kt`
2. `app/src/main/java/com/feizhela/headband/bluetooth/BluetoothDataSource.kt`
3. `app/src/main/java/com/feizhela/headband/bluetooth/BluetoothRepository.kt`
4. `app/src/main/java/com/feizhela/headband/bluetooth/AndroidBluetoothClient.kt`
5. `app/src/main/java/com/feizhela/headband/recording/RecordingSessionRepository.kt`
6. `app/src/main/java/com/feizhela/headband/recording/AcquisitionService.kt`
7. `app/src/main/java/com/feizhela/headband/storage/RecordingDao.kt`
8. `app/src/main/java/com/feizhela/headband/storage/RecordingSessionEntity.kt`
9. `app/src/main/java/com/feizhela/headband/storage/RawPacketEntity.kt`
10. `app/src/main/java/com/feizhela/headband/storage/RecordingDatabase.kt`
11. `app/src/main/AndroidManifest.xml`
12. `app/src/main/res/xml/backup_rules.xml`
13. `app/src/main/res/xml/data_extraction_rules.xml`
14. `app/build.gradle.kts`
15. `app/schemas/com.feizhela.headband.storage.RecordingDatabase/1.json`
16. `app/src/main/java/com/feizhela/headband/MainActivity.kt`
17. `app/src/main/java/com/feizhela/headband/ui/FeizhelaApp.kt`

测试：

18. `app/src/test/java/com/feizhela/headband/bluetooth/BluetoothDataSourceTest.kt`
19. `app/src/test/java/com/feizhela/headband/bluetooth/BluetoothRepositoryTest.kt`
20. `app/src/test/java/com/feizhela/headband/recording/RecordingSessionRepositoryTest.kt`
21. `app/src/androidTest/java/com/feizhela/headband/storage/RecordingDatabaseTest.kt`
22. `app/src/test/java/com/feizhela/headband/BluetoothClientContractTest.kt`
23. `app/src/androidTest/java/com/feizhela/headband/ComposeNavigationTest.kt`

需求与背景：

24. `framework.md`（读取第 50–99 行；重点第 62–82 行）
