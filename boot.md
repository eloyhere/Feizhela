# 菲哲拉 Android 项目启动与技术现状（boot）

**盘点日期：** 2026-11-03  
**被盘点交付目录：** `R:\菲哲拉-1.1.0-交付`  
**核心应用版本：** 菲哲拉 1.1.0（versionCode 5）  
**当前定位：** 可安装的入睡观察验证版，不是已通过销售级或医疗验收的睡眠识别产品。

> 本文按交付目录中的 Flutter/Dart 应用、Android 构建配置、EOG 研究包、头带固件、说明和验收记录汇总。涉及真实性能时明确标出证据范围；短测和公开数据集不能替代 Android 真机整夜或同步 PSG 验证。

## 1. 目录组成

交付包主要包括：

- `菲哲拉-Android源码/`：Flutter Android 工程，Dart 业务代码位于 `lib/`，测试位于 `test/`，Android 壳与 Gradle 配置位于 `android/`。
- `菲哲拉-算法研究/`：EOG 特征与训练脚本、模型、公开数据清单/元数据、复现和评估材料。
- `菲哲拉-头带固件/`：Seeed XIAO nRF52840 Sense 固件源码、烧录文件、烧录脚本和测试记录。
- 根目录使用说明、算法研究与评估、销售级验收岗位记录、APK、校验清单等。

应用构建环境记录为 Flutter 3.29.3 / Dart 3.7.2、JDK 17、Gradle 8.10.2、AGP 8.7.0、Kotlin 2.1.10、Android SDK 35、NDK 27.0.12077973。工程包名 `cn.feizhela.headband`，最低 Android SDK 23；正式签名密钥不在交付包中。

## 2. 当前系统架构

### 2.1 总体分层

当前项目不是原生 Kotlin Android 应用，而是 **Flutter/Dart 跨平台 UI + Android 平台能力插件/MethodChannel + BLE 头带固件** 的组合：

```text
Flutter UI (lib/src/features, lib/src/shared)
        │ Provider / ChangeNotifier
        ├── OssmmBluetoothService ── flutter_blue_plus ── BLE GATT 头带
        │        ├── DataSample 解析、样本计数器和时间戳
        │        ├── CsvWriterUtil 写原始记录并生成密码 ZIP
        │        └── SleepObservation 在线 30 秒窗口分析
        ├── EogSleepModel（本地 JSON 模型，无云端推理）
        ├── SleepDiaryStore（flutter_secure_storage）
        ├── LocationService / 权限、生命周期和前台服务
        └── MethodChannel (ossmm/files) 文件导出等 Android 原生接口

头带 Seeed XIAO nRF52840 Sense
        ├── EOG、脉搏原始 ADC
        ├── LSM6DS3 加速度计/陀螺仪
        └── BLE 通知：每包 10 个样本，18 字节/样本
```

### 2.2 App 入口与界面

- `lib/main.dart` 初始化 Flutter、载入 `assets/models/eog_model.json`，注册 Provider 服务，设置主题/导航和前台记录服务。
- `HomeScreen` 提供“今夜、记录、头带、我的”四入口；`NightDashboard` 显示连接、观察和基线状态；其他页面负责扫描/连接、数据曲线、产品信息、回看、日记及导出。
- BLE 主状态集中在较大的 `OssmmBluetoothService`（单个文件约 1,800 行），它同时处理扫描、权限、连接、特征发现、通知订阅、原始数据解码、记录生命周期、重连和手动马达测试。
- UI 与服务主要使用 `provider` / `ChangeNotifier`，导航异步提示通过全局 `appNavigatorKey`。
- Android 壳声明 BLE、位置、前台服务和电池优化相关权限；长记录配置 connected-device 前台服务、通知和 wakelock。文件导出通过 `ossmm/files` MethodChannel。

### 2.3 头带数据协议与记录

- 固件目标采样率 104 Hz；每个 BLE 通知数据包 180 字节，即 10 × 18 字节样本。各字段为 uint16 小端：计数器、EOG、脉搏原始值、三轴加速度和三轴角速度。
- App 按 16 位计数器计算样本时间，单独记收包时间；不以插值伪造断线数据。当前数据模型的通道字段命名 `hr`，但实际装的是原始脉搏 ADC 值，不能据此认为已计算心率。
- `CsvWriterUtil` 保存原始 CSV，并将其与观察报告打包为密码 ZIP；睡眠日记另以安全存储保存，导出为单独 JSON。保存失败有保留/恢复 CSV 的路径。
- ZIP 使用传统 ZipCrypto，说明文档明确提醒其不是强加密；恢复 CSV 可能未加密。用户需要妥善保存本地解压密码并自行备份。

### 2.4 Android 原生构建与权限

- Gradle Kotlin DSL 管理 app 与 Flutter 插件，namespace/applicationId 为 `cn.feizhela.headband`，release signing 从未提交的 `android/key.properties` 和密钥文件读取。
- Manifest 包含 BLE scan/connect/advertise、旧版蓝牙权限、位置权限、foreground service/connected-device、请求忽略电池优化等声明；真实权限行为取决于 Android 版本、厂商 ROM 和用户选择。
- 交付记录明确指出：Android 真机安装、运行时权限弹窗、通知栏停止、后台整夜、文件选择器导出以及不同厂商省电策略尚未完成目标手机验证。

## 3. 当前算法与处理流程

### 3.1 训练模型

模型标识 `feizhela-eog-rf-0.1`，推理规则 `eog-rf-quality-causal-0.2`。模型仅用 EOG 单通道输入；加速度和陀螺仪用于当前工程规则的活动拒绝，不是训练好的多模态分类器；脉搏通道仅作原始展示/记录。

1. 使用 30 秒片段。EOG 按 20 Hz 分箱均值、片段去均值并标准化。
2. 提取 13 个特征：偏度、峰度、线长、过零率、Hjorth mobility、Hjorth complexity、六个相对功率频带（0.1–10 Hz）和谱熵。
3. 将当前片段特征和当前/过去最多三个合格片段的因果均值拼接，得到 26 维向量，不读取未来片段。
4. 64 棵随机森林输出三类树投票：清醒、N1/N2 合并、其他睡眠合并。第三类合并 N3/N4/REM，不能细分这些阶段。分数是投票，不是校准置信度。
5. 模型在设备本地运行，不依赖云端服务。

### 3.2 在线质量门与候选规则

`SleepObservation` 在采样时间上组成 30 秒 epoch；至少两分钟完整、安静的清醒片段用作本次 EOG 基线。缺样、异常、平直/饱和信号或活动会阻止分类，并清除/限制时序上下文。完整模型评估需要覆盖率至少 99.5%；代码另设缺样、EOG 波动/削顶、协议范围检查；活动门限为加速度合成指标 >0.035g 或角速度 >12°/s。这些是工程门限，尚未完成本头带标定，不是临床阈值。

模型浅睡合并类投票至少 0.60、EOG 幅度不超过基线两倍、质量/活动条件满足，并连续三个 30 秒 epoch 才记作“待核对候选”。断流不补成睡眠；不把候选持续时间等同总睡眠时间。候选只供回看，不触发自动马达反馈。

### 3.3 睡眠日记

用户输入入睡、最终醒来、夜间清醒分钟和主观感受。应用按时间跨度减去清醒分钟得到“自报估算睡眠时长”，保存来源标识 `self_reported`。这不是设备测得的总睡眠时间，也不产生客观睡眠质量分数。

## 4. 算法验证现状

研究包使用 Sleep-EDF Expanded 1.0.0 的 12 名受试者、每人第一晚入睡附近 150 分钟；按人划分训练 8 人（2400 段）、验证 2 人（599 段）、独立测试 2 人（599 段）。因此测试样本段数不是独立受试者数，测试仅代表早夜窗口和有限公开 EOG 数据。

独立测试三分类结果：准确率 69.12%，macro-F1 0.664，平衡准确率 69.92%，Cohen's kappa 0.524。浅睡合并类 precision 83.40%、recall 72.48%。阈值筛出的 129 段中 123 段为浅睡类（95.35%），只覆盖测试片段的 21.54%；该数字不是整夜准确率、个人置信度或完整连续候选链路的 precision。

训练脚本报告了固定随机种子、按人隔离以及文件/窗口哈希校验。研究包提供 Python 训练实现、Dart 特征/模型推理、模型权重和测试向量；报告称独立重训逐树一致，599 个留出向量的 Dart 与 Python 森林推断一致。此处证明的是软件复现/跨语言一致性，不证明头带人体有效性。评估 JSON 明确 `headbandValidationPassed=false`、`overnightValidationPassed=false`、`commercialReleaseGatePassed=false`。

## 5. 架构与算法优缺点

### 5.1 优点

- 本地离线推断，原始数据和日记默认留在设备，无云端账号/上传依赖。
- BLE、模型、观察、日记、文件存储及功能页面已有一定领域目录划分；Provider 使 UI 可订阅连接/记录状态。
- 质量门采取拒绝判断而非用缺失信号补造结论；按设备时间/计数器处理缺口，并保留原始数据与观察理由供回看。
- 候选规则采用因果时序，不看未来；清醒基线、活动复核与连续证据有助于抑制孤立噪声造成的提示。
- 随机森林体积小、计算可控、手机端可解释性相对直接；公开数据按受试者隔离，测试集没有用于阈值选择；Python/Dart 对照提高实现可信度。
- 训练、数据来源、限制、组件和数据许可有随包材料；产品边界避免把自报日记或原始脉搏说成客观睡眠结论。

### 5.2 局限与技术债

- BLE 服务承担过多职责且文件很大：协议/权限/连接/记录/存储/观察混在一个 ChangeNotifier 服务中，状态耦合和回归风险较高。需要重构时先稳定服务接口和行为测试，再拆分扫描连接、协议解析、采集会话、存储/导出、反馈命令等职责。
- 多处使用字符串 key、动态 JSON 和硬编码协议常量；模型资产缺失时初始化会静默置空模型，需确认 UI 对算法不可用状态的处理。测试覆盖业务模拟较多，Android 平台真实行为仍待验证。
- 当前 BLE 实际链路吞吐、长时间缓存增长、断线恢复、前后台时间戳漂移、系统进程回收、设备厂商后台限制和存储耗尽等尚无整夜真机证据。
- EOG 特征做每片段标准化，可降低偏置/增益影响，但不能消除电极位置、接触质量、滤波、采样、硬件 ADC 和人群差异；归一化也可能丢失幅值信息，故另设基线门但基线本身未对本硬件验证。
- 树投票分数没有概率校准；森林三个类别不等于标准完整睡眠分期。早夜窗口、每类分布及仅两名测试受试者导致泛化结果不稳定。
- 20Hz 下采样、谱功率、熵等实现虽有 Python/Dart 数值对照，但研究包中的独立性检查有限；后续算法改动必须重跑完整特征一致性及留出集评估。
- 质量阈值 0.035g、12°/s、覆盖率、削顶等尚缺本头带物理标定和接触质量硬件；不能据此声称临床意义。
- 传统 ZipCrypto 弱于现代加密；敏感日记和记录的备份、密码轮换/恢复、密钥遗失处置策略需要继续评估。

## 6. 已知问题与未完成验证

### 交付记录已明确的未完成项

- 无头带与同步 PSG 的配对数据；没有真实产品域的夜晚睡眠分期验证。
- 未验证自动总睡眠时间、入睡潜伏期、夜间觉醒、睡眠效率和客观睡眠质量。
- Android 用户手机的安装、BLE 权限/系统配对、持续后台、通知栏停止、选择器导出及主流厂商 ROM 行为尚待真机验证。
- 没有完整 8 小时以上的丢样、恢复、内存、磁盘、电池和温升测试。
- 固件记录显示：连接初期约 2 秒协商有计数缺口；初始严格零缺口测试失败。随后 Windows USB+BLE 的稳定阶段和断开重连短测通过，但平台不是 Android，也不代表整夜零丢样。
- 当前自动反馈关闭。头带固件支持手动 0x01 马达特征，App 手动测试需要用户主动确认，录制期间不允许；未来如开发算法触发反馈，需独立影子模式和安全验收。
- 许可/品牌边界需在后续重构、再发布和商用时持续保留 OSSMM GPL、第三方组件许可、模型数据 ODC-By 归属及签名连续性。

### 建议优先级

1. **先建立可复现基线：** 保存当前 APK/模型/固件哈希、工具链和测试报告；确认测试全量运行与源代码/模型版本对应。每次重构要求测试通过且 BLE 采集行为不回归。
2. **Android 真机工程验证：** 覆盖目标 Android 版本与厂商；检查冷启动授权、扫描、配对、连接、后台 8 小时、通知停止、断连重连、时间戳/计数缺口、异常结束和文件导出/删除。
3. **硬件数据质量标定：** 同步记录头带原始信号与参考设备/PSG；量化电极接触、ADC/滤波/采样偏差和运动场景，重新评估拒判阈值。
4. **临床/产品有效性研究：** 冻结协议、目标人群、事件定义、样本量与阈值；按受试者/夜晚隔离独立测试集，报告每人误差和不确定性、覆盖率、每小时误报、事件延迟及失败夜晚，不只报告筛选后 precision。
5. **再考虑产品化/自动反馈：** 目前没有通过商业发布门槛。自动反馈应先在影子模式验证，再独立评估误触发、强度/次数上限、暂停/退出和对睡眠的影响。

## 7. 接手改造时的约束与建议

- 当前是 Flutter Android 项目；如目标改为原生 Kotlin，先做功能/数据协议迁移清单，保留 BLE UUID、18 字节样本字段、计数器回卷规则、时间戳和记录兼容性。
- 不要在重构中把“待核对候选”改名为已确认睡眠，也不要将树投票分数显示成个人准确率/置信度、把原始脉搏解释为心率/HRV，或由候选触发马达。
- 保留缺样/低质量拒判及原始数据；不要对断线窗口插值成可分类睡眠。
- 算法更新需版本化特征 schema、模型文件和门限；至少运行已有 Dart/Python 留出向量一致性、特征 fixture、连续规则、质量拒绝与存储故障测试，并单独跑人体域验证。
- 建议第一步把 `OssmmBluetoothService` 行为边界文档化并补充可测试协议状态机，再按采集生命周期拆分；避免一次性重写 BLE 和记录路径。
- 当前数据格式和模型不适合作为已验证睡眠医疗设备的承诺。对外说明应保留验证版边界和数据隐私/加密限制。

## 8. 主要源码与证据索引

相对于交付根目录：

- Flutter 架构与限制：`菲哲拉-Android源码/README.md`、`菲哲拉-Android源码/pubspec.yaml`、`菲哲拉-Android源码/lib/main.dart`、`菲哲拉-Android源码/lib/src/features/home/screens/home_screen.dart`。
- BLE/协议/记录：`菲哲拉-Android源码/lib/src/core/services/bluetooth_service.dart`、`菲哲拉-Android源码/lib/src/core/models/data_sample.dart`、`菲哲拉-Android源码/lib/src/core/utils/csv_writer.dart`。
- 模型和规则：`菲哲拉-Android源码/lib/src/core/models/eog_sleep_model.dart`、`菲哲拉-Android源码/lib/src/core/models/sleep_observation.dart`、`菲哲拉-算法研究/train_eog.py`。
- 评估：`菲哲拉-算法研究/算法研究与评估.md`、`菲哲拉-算法研究/evaluation.json`、`菲哲拉-算法研究/README.md`。
- 日记/Android 平台：`菲哲拉-Android源码/lib/src/core/models/sleep_diary.dart`、`菲哲拉-Android源码/lib/src/core/services/sleep_diary_store.dart`、`菲哲拉-Android源码/android/app/src/main/AndroidManifest.xml`、`菲哲拉-Android源码/android/app/build.gradle.kts`。
- 测试：`菲哲拉-Android源码/test/sleep_model_test.dart`、`菲哲拉-Android源码/test/recording_storage_test.dart`、`菲哲拉-Android源码/test/product_ui_test.dart`。
- 设备证据：`菲哲拉-头带固件/README.md`、`菲哲拉-头带固件/烧录后稳态与重连检查.json`、`菲哲拉-头带固件/初始严格检查-存在缺口.json`。
- 产品验收边界：`菲哲拉-1.1.0-使用说明.md`、`菲哲拉-1.1.0-销售级验收与岗位记录.md`。
