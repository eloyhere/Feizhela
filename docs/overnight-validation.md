# 加速长记录与服务恢复验证

此测试验证 8 小时规模的数据处理与归档恢复。它使用 MuMu、真实磁盘 Room、生产录制写入器与生产频谱估计器；输入在 BluetoothRepository 边界生成并按写入进度施加背压。它不是实际运行 8 小时，也没有模拟射频吞吐、Android 后台冻结或电池消耗。

## 可重复执行

先串行构建，不要在多个终端并行运行 Gradle：

```powershell
$env:JAVA_HOME='R:\Android\android-studio\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
```

安装 debug 应用和测试 APK，然后在 MuMu 执行：

```powershell
& 'R:\Android\Sdk\platform-tools\adb.exe' -s 127.0.0.1:16384 install -r app\build\outputs\apk\debug\app-debug.apk
& 'R:\Android\Sdk\platform-tools\adb.exe' -s 127.0.0.1:16384 install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
& 'R:\Android\Sdk\platform-tools\adb.exe' -s 127.0.0.1:16384 shell am instrument -w -e overnight true -e class com.feizhela.headband.simulation.OvernightArchiveTest com.feizhela.headband.test/androidx.test.runner.AndroidJUnitRunner
```

必须检查 instrumentation 输出中的 `OK` 与失败详情，adb 命令退出码本身不代表测试成功。长记录测试为显式选择项，未传 `-e overnight true` 时通过 assumption 跳过，不能计为长记录验证已执行。

测试成功后，应用外部文件目录中的 `overnight-validation.json` 保存规模、SHA-256、耗时、磁盘和 Java 堆采样数据。测试数据库使用独立随机名称，finally 仅清除自己创建的数据库；不删除正式 `recordings.db`。

## 覆盖内容

- 299,520 个 180 字节固件包、2,995,200 个样本、960 个 30 秒窗口；覆盖多个 uint16 counter 回卷。
- 生产 RecordingSessionRepository 的批写、尾批停止和无 gap 断言。
- 活动录制中替换分析 owner，重新读取现有归档并 upsert；继续录制不依赖原 owner 的内存 cursor。
- 每个 epoch 的索引、起点、样本数、谱结果质量与有限 residual。
- 生产分析分页限制不大于 128。分页观测不能证明整个进程内存恒定。
- 逐包核对所有字节、接收时间、连续序号与设备 counter；生成和读回的 SHA-256 必须一致。
- 关闭/重开磁盘数据库后再次核对归档和结果；全量终态重放后 epoch 必须逐字段相同。
- 记录录制与并发分析耗时、最终分析完成耗时、活动 owner 重建耗时、终态全量重放耗时、DB/WAL/SHM 字节数，以及每 512 包采样的 Java 已用堆高值。堆采样不是进程 RSS，也不是精确峰值。

服务恢复测试使用实际 AcquisitionService 与 debug 模拟传输，入口为 `com.feizhela.headband.simulation.AcquisitionServiceRecoveryTest`。它在实际通知产生完整窗及部分尾窗后停止转发，等待归档确认，再 unbind + stopService。验证 `INTERRUPTED/SERVICE_DESTROYED`、原始数据与派生尾窗保存、旧传输关闭；重建的新服务保持 idle，不自动 scan/connect/subscribe，并能开启独立的新录制。该路径测试正常 onDestroy，不等价于强杀进程。由于停止前等待已归档确认，它也不强制制造销毁时尚未提交的 raw batch。

## 实测记录

2026-11-03，在 MuMu `NCO-AL00 / API 35` 执行显式选择的长记录测试，instrumentation 报告 `OK (1 test)`，总测试时间 120.872 秒。原始机器报告见 [overnight-validation-result.json](overnight-validation-result.json)。

| 指标 | 实测 |
| --- | ---: |
| 原始包 / 样本 / epoch | 299,520 / 2,995,200 / 960 |
| 原始 payload 总字节 | 53,913,600 |
| 录制及并发分析至 raw stop 完成 | 73,404 ms |
| 所有 epoch 完成 | 73,445 ms |
| 中途活动 owner 重建 | 14,598 ms |
| 新 owner 全量终态重放 | 28,974 ms |
| 关闭前 DB + WAL + SHM | 107,805,608 bytes |
| 每 512 包采样的 Java 已用堆高值 | 137,994,992 bytes |
| 分析读取上限 | 128 包/页 |
| 最慢录制批次写入 | 58 ms |

payload SHA-256：`d80385b686e6d47da235549c382f3e40d3aa8747ccba6d0acfffb8de0c68c834`。重开数据库前后均逐包重生期望字节、校验时间戳/counter 并重新计算摘要；全量终态重放后的 epoch 与首次结果逐字段相同。

这些是单次模拟器加速测试数据。活动 owner 重建包含前半段归档重读及重估；终态重放耗时表明目前每个 service owner 启动都会重算历史会话的策略存在明显成本。测试确认可重放修复，但不证明多个历史夜晚的启动延迟可接受。磁盘值包含 WAL，不能当作 checkpoint 后稳定数据库大小；Java 堆采样不等于实时采集所需内存或泄漏判断。

同轮最终构建的 APK 在 MuMu 上执行以下四个 class，instrumentation 报告 `OK (10 tests)`，总计 4.566 秒：

- `AcquisitionServiceRecoveryTest`：1 项，真实服务销毁/重建、终态归档和独立新录制。
- `SignalAnalysisIntegrationTest`：5 项，生产链路接入与分析隔离。
- `SignalAnalysisRecoveryTest`：2 项，部分派生结果修复和质量门。
- `AcquisitionServiceTest`：2 项，启动恢复与服务生命周期。

```powershell
& 'R:\Android\Sdk\platform-tools\adb.exe' -s 127.0.0.1:16384 shell am instrument -w -e class com.feizhela.headband.simulation.AcquisitionServiceRecoveryTest,com.feizhela.headband.simulation.SignalAnalysisIntegrationTest,com.feizhela.headband.simulation.SignalAnalysisRecoveryTest,com.feizhela.headband.recording.AcquisitionServiceTest com.feizhela.headband.test/androidx.test.runner.AndroidJUnitRunner
```

最终 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` 成功；XML 汇总为 14 suites、110 tests、0 failures、0 errors。构建时先移除了导航测试中的无效 `assertDoesNotExist` 顶层导入（该断言是节点 interaction 的成员方法），未改变断言本身。KSP 增量缓存出现 `NoSuchFileException: app/build/kspCaches/debug/backups` 后，使用 `'-Pksp.incremental=false'` 完成重建；这是本次构建参数，没有修改项目默认配置。

长记录与服务测试在两个独立 instrumentation invocation 中执行。此前 38 项选定仪器测试的记录仍见 acquisition 文档；本轮没有重新执行全部 UI 或全部仪器测试，也不把重叠用例相加成新总数。

## 后续实机检查

实际头环仍需单独核对协议字段、端序、采样率与单位标定。真实墙钟 8 小时验证需记录前台通知、锁屏、省电、蓝牙断开、进程强杀与重新进入后的数据状态，并使用外部基准检查丢包与设备时钟漂移。这里的合成信号与频谱拟合结果不验证睡眠分类或生理准确性。
