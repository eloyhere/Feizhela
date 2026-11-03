# 菲哲拉 Feizhela · 头带睡眠观察（Android 原生）

Kotlin + Jetpack Compose 重写的菲哲拉头带配套应用：在手机本地连接 BLE 头带，归档原始 EOG / IMU
数据，按 30 秒片段计算采集质量与频谱证据，并提供记录回看、自报睡眠日记与数据导出。

> **产品边界**：本应用是研究与自我观察原型，**不是医疗器械**，不提供诊断、治疗或睡眠分期结论。
> 当前版本没有睡眠分期模型；界面只展示采集质量与频谱证据，不输出“浅睡 / 深睡 / REM”、总睡眠
> 时间或客观睡眠质量。

## 已实现的真实链路

- **BLE 采集**：扫描、连接、服务发现、通知订阅；固件 UUID 固定，通知为 180 字节
  （10 × 18 字节样本，标称 104 Hz，小端 uint16 原始计数）。
- **前台采集服务**：连接与录制生命周期独立于 Activity；进程重建后，未正常结束的会话会被标记为
  `INTERRUPTED / PROCESS_RESTART`，不会把断连伪装成连续记录。
- **Room 本地归档**：会话、原始包、采集缺口、30 秒片段及分析结果，含数据库版本迁移。
- **质量门**：覆盖率、设备计数器缺口 / 重复 / 回卷、接收时间歧义、削顶 / 平直信号、分析失败。
- **历法式频谱精估**：每个片段输出 3 个分量（幅度 / 频率 / 相位）、实际分析窗长、残差比与验历结果。
  这是频谱证据，**不是睡眠分期**。
- **记录列表与详情**：覆盖率、缺口、质量摘要、片段状态时间线、小时覆盖率、趋势与频谱分量。
- **数据导出**：通过 SAF 选择保存位置，导出 ZIP（每会话 CSV + 摘要 JSON）；支持删除单条或全部记录。
- **自报睡眠日记**：本地保存，与设备观察并列显示、不混合、不换算。
- **运行时权限**：Android 12+ 使用 `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`；Android 11 及以下使用定位权限。

## 明确不做 / 已知限制

- 无睡眠分期模型，不提供睡眠阶段、总睡眠时间、睡眠效率或“确认睡眠”。
- 原始脉搏通道是 ADC 计数，不是已验证的心率或 HRV。
- 频谱残差比、分量幅度都不是概率或个体准确率。
- 自动马达反馈保持关闭。
- 尚未用本头带 + 同步 PSG 做人体有效性验证；公开数据集指标不能替代整夜真机验证。
- 导出 ZIP **未加密**；请自行保管导出文件。
- Manifest 仍允许 Android 自动备份（备份 / 恢复规则尚未收紧），敏感数据的备份策略仍需按目标机型确认。

## 架构

```text
app/src/main/java/com/feizhela/headband/
├── bluetooth/     BLE 传输、固件协议常量与 18 字节样本解码
├── permission/    BLE 运行时权限契约
├── recording/     采集服务、录制仓库、30 秒片段组装、频谱分析
├── signal/        历法式频谱精估（Java 实现）
├── storage/       Room 实体/DAO/迁移、录音库、CSV/JSON 导出
└── ui/            Compose 页面、图表组件、状态适配
```

- UI 只渲染不可变状态并发送动作，不直接解析 BLE 包、不直接访问 DAO。
- 采集服务拥有 BLE 与录制；UI 通过进程内绑定读取状态、发送命令。
- Room 是 UI 查询与进程重建后的事实来源；图表不把补点或插值回灌到模型/统计。

## 数据与存储

| 数据 | 位置 |
| --- | --- |
| 会话、原始包、缺口、片段 | 应用私有目录 `recordings.db`（Room） |
| 自报睡眠日记 | 应用私有 `SharedPreferences("diary")` |
| 导出文件 | 由用户通过系统文件选择器（SAF）指定，应用不写入公共目录 |

## 头带协议

| 项 | 值 |
| --- | --- |
| Service UUID | `5aee1a8a-08de-11ed-861d-0242ac120002` |
| Data Characteristic | `405992d6-0cf2-11ed-861d-0242ac120002` |
| 通知长度 | 180 字节 = 10 样本 × 18 字节 |
| 每样本 | 9 个 uint16 小端：counter、EOG、pulse、accel x/y/z、gyro x/y/z |
| 标称采样率 | 104 Hz |

马达与电源特征 UUID 记录在 `HeadbandProtocol` 中，但自动采集与反馈不会写入它们。

## 构建

需要 JDK 17+ 与 Android SDK 37（`compileSdk = 37`）。SDK 路径写在
`local.properties`（该文件不入库）。

Windows（PowerShell）：

```powershell
$env:JAVA_HOME = "R:\jdk-17.0.14.7-hotspot"   # 本机已验证的 JDK 17
.\gradlew.bat :app:assembleDebug --console=plain
```

单元测试与设备测试：

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain   # 需要连接设备/模拟器
```

`app/schemas/` 保存 Room 导出的数据库 schema；新增迁移时需同步更新并运行迁移测试。

### Debug 模拟传输

`app/src/debug/java/com/feizhela/headband/simulation/` 提供一个不接触 Android BLE 的模拟传输，
用于在模拟器上跑通真实采集管线（`SimulationRun`）。它只存在于 debug 构建；release 构建始终使用
真实 `AndroidBluetoothClient`。

## 文档

- [`boot.md`](boot.md)：1.1.0 交付版的架构、算法与验证现状盘点。
- [`framework.md`](framework.md)：原生 Compose 重构的边界、数据模型与实施顺序。
- [`docs/acquisition.md`](docs/acquisition.md)：采集与存储实现说明。
- [`docs/overnight-validation.md`](docs/overnight-validation.md)：整夜验证记录与结果。

## 许可

本项目原创代码采用 [Apache License 2.0](LICENSE)。

第三方组件（AndroidX / Jetpack Compose / Kotlin / Room 等）保留其各自许可证。若本仓库中包含来自
OSSMM 或其他上游项目/研究材料的代码、协议实现或数据，相关文件仍受其原始许可证（例如 GPL-3.0）
约束；Apache-2.0 不改变这些既有义务。分发或商用前请复核第三方许可与品牌边界。
