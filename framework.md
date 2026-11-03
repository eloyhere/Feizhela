# 菲哲拉 Native Android / Compose 重构框架

**目标：** 将现有菲哲拉 Android 产品重构为原生 Kotlin + Jetpack Compose。本文定义 UI、数据、设备采集、睡眠观察算法和图表的建议边界；不是将 Flutter 代码搬进 Compose，也不授权本轮修改应用源码。

**状态边界：** 当前工作区已有一个 Kotlin/Compose 导航样板，但仍是模板界面；交付目录中的 Flutter 工程是此前验证版的行为与数据格式参考，不是新的 UI 技术栈。算法仍是研究用途，界面应表达观察证据和数据局限，不应把未验证分期包装成客观睡眠结论。

## 1. 先处理的迁移约束

迁移前先冻结产品身份、安装升级和数据兼容策略：

- 交付应用 `applicationId=cn.feizhela.headband`、`minSdk=23`；当前 Compose 工程 `applicationId=com.feizhela.headband`、`minSdk=24`。包名不同意味着 Android 将其视为另一款应用，不能作为原应用的原位升级；旧应用数据也不会自动迁移。若要给旧用户发更新，须先决定是否恢复旧 applicationId，并核对原签名密钥是否可用。
- 旧构建记录为 AGP 8.7.0 / Gradle 8.10.2 / Kotlin 2.1.10 / SDK 35；当前工程版本目录是 AGP 9.5.0 / Gradle 9.6.0 / Kotlin 2.2.10 / compileSdk 与 targetSdk 37。需把版本组合与实际 JDK、CI、依赖插件跑通后再视为项目基线，不应只因同步成功就认定发布构建可靠。
- 新工程仍保留不少 Java/XML Navigation 模板资源和不使用的 legacy 依赖；Gradle 排除了 Java 源码，但模板文件还会增加理解和维护成本。确认无引用后逐步清理，而非与 Compose 页面长期双轨维护。
- Manifest 当前 `allowBackup=true`，backup / data-extraction 规则仍是模板空规则。由于睡眠记录和日记可能敏感，应先决定云备份/设备迁移策略，并用真实 Android 版本验证排除规则；不要把“本地处理”误当成“数据绝不离开设备”。
- 当前 UI 的导航项是 Transform/Reflow/Slideshow/Settings，页面仍是占位页；FAB 也显示模板 Action。Compose 接入真实设备和数据前，应去除这些模板交互，先定义菲哲拉的信息架构和工作流。

## 2. 目标架构

```text
Compose Screens / reusable components
            │ UI State + UiAction (单向数据流)
            ▼
Screen ViewModels (lifecycle-aware StateFlow)
            │ 调用用例；不依赖 Android View / BluetoothGatt 细节
            ▼
Domain: Observation / Session / Epoch / Quality / Chart models
            │ 接口
            ▼
Repositories / session orchestration
   ├── BLE data source + protocol decoder
   ├── Room session/sample/epoch storage
   ├── Model inference + quality gates
   ├── Export / import / backup policy
   └── Android foreground collection service
            │
       BLE GATT headband
```

### 2.1 依赖方向与职责

- **UI 层：** Compose screen 只收集不可变 `UiState`、渲染组件并发送动作；不得自行解析 BLE 包、拼 CSV、判睡眠阶段或直接访问 DAO。可视化组件通过参数接收数据，无业务状态隐藏在 Canvas 内。
- **ViewModel：** 将 Repository/UseCase 数据转换为屏幕状态；用 `StateFlow` 暴露状态、协程处理用户意图。连接中、已连接、录制、丢包、保存中、失败等应是明确状态，而非多个可矛盾布尔值。短生命周期 UI 逻辑保留在 Composable；共享业务状态放 ViewModel/Domain。
- **Domain：** 定义设备无关的会话、采样、30 秒 Epoch、质量状态、模型分数和候选事件；模型门限/标签映射集中且有版本号，避免页面和图例各写一套业务判断。
- **Data：** BLE、数据库、文件、模型资产各自为独立数据源，通过 Repository 组合。记录详情从持久化会话读取，而不是依赖仅在内存里保留的样本列表。
- **采集服务：** 长时间 BLE 收集不依赖 Activity/Compose 可见。使用前台服务及 Android BLE 连接约束时，服务拥有采集生命周期；Room 是 UI 查询和进程重建后的事实来源。UI 与服务通过可观察的持久化/IPC 状态协调，不能假设进程被杀后 BLE 回调仍存在。
- **导航：** 使用产品域目的地，如今夜、记录详情、信号检查、设备、日记、设置；列表传稳定 session ID，不传大数组或文件内容。Navigation 保存轻量参数，持久业务数据从 Repository 重新查询。

推荐最小模块/包边界按责任划分：`app`（入口、导航、主题）、`core/model`、`core/ble`、`core/observation`、`core/storage`、`core/export`、`feature/night`、`feature/session`、`feature/device`、`feature/diary`、`ui/components/charts`。早期可以先作为单 Gradle module 的包，不必为每个目录立刻拆独立 Gradle module。

### 2.2 关键数据模型

持久化和界面均使用明确单位、时钟来源和质量标签。建议至少定义：

- `RecordingSession`: `sessionId`、协议/固件/应用/模型版本、起止时间、状态、停止原因、持久化状态。
- `RawSample`: 单调计数器、设备采样时钟/映射后的时间戳、手机接收时间、EOG 原始 ADC、脉搏原始 ADC、加速度、陀螺仪、序号缺口标记。
- `ObservationEpoch`: 起始时间、30 秒窗口、收到样本数/期望样本数、缺样跨度、质量状态与原因、活动指标、各类 tree-vote 分数、规则版本、候选状态。
- `SleepDiary`: 明确标记 `self_reported`，不可与设备 epoch 合并伪装为同源测量。
- `ChartSeries`: 明确 X 轴单位/时区、Y 轴单位、来源、窗口长度、采样/降采样方法、缺失区间、可否比较，以及模型验证状态。

图表消费面向 UI 的精简聚合结果；原始 104 Hz 数据应写入持久层，按视窗加载/抽稀，不要把整晚原始点常驻 ViewModel 或一次性传入 Compose。

## 3. BLE 与采集的数据链

交付固件协议提供可兼容基线：每包 180 字节 = 10 个 × 18 字节，小端 `uint16` 字段依次为 16 位计数器、EOG、原始脉搏、加速度 xyz、角速度 xyz；目标采样率 104 Hz。需把协议实现写成 Kotlin 单元测试，不要依赖 UI 做解析。

建议数据路径：

```text
BLE callback
  → 校验包长/解码/校验计数器回卷
  → 记录 device timestamp、receivedAt、gaps
  → 有界批次写入 Room/原始归档
  → 30 秒质量与算法窗口
  → 持久化 Epoch/候选证据
  → Repository Flow
  → ViewModel UiState
  → 时间线/信号/统计组件
```

避免 BLE 每来一个包就触发整页重组：callback 合并为小批次，UI 曲线按目标显示速率发布数据（如 5–10 Hz 或低于屏幕刷新），原始数据仍按采集要求保存。图表按时间窗做 min/max envelope 或等距抽样，避免简单每 N 点取一个导致窄尖峰/瞬态被完全漏掉；缺失片段显式画 gap，不连接成连续曲线。数据库使用事务/批写并测量写入积压、磁盘占用和异常关闭恢复。

Android 官方 BLE 指南指出后台 GATT 连接仍依赖进程存活；进程被杀后连接会关闭。因此“前台服务 + wakelock”不是进程永生保证。必须在目标系统/厂商手机测真实后台时长、权限撤销、蓝牙开关、断连、进程回收及恢复语义；界面准确显示采集已暂停/数据不完整，而不是无限延长 session。

## 4. 算法重构中的边界与审计发现

### 4.1 保留可追溯的算法证据

旧交付算法是单通道 EOG 随机森林研究基线，不是多模态睡眠分期：30 秒 epoch、20 Hz bin mean、13 个特征、当前及过去片段因果均值构成 26 维输入、64 树、三类（清醒 / N1-N2 合并 / N3-N4-REM 合并）。IMU 用于独立运动门，不是训练模型输入；脉搏是原始 ADC，不是已验证 BPM 或 HRV。

旧研究报告的留出集来自 Sleep-EDF 12 人、每人第一夜早夜窗口；测试集仅受试者 10–11 两人，共 599 段。报告测试准确率 69.12%、macro-F1 0.664、balanced accuracy 69.92%；浅睡合并类 precision 83.40%、recall 72.48%。阈值筛选的 129 段有 123 段命中（95.35%）只覆盖 21.54% 的测试 epoch，不是全时段准确率或连续候选事件准确率。公开数据按受试者划分与 Python/Dart 推理对照是有价值的软件证据，但不能证明新用户/头带/整夜效果。

Compose 不承载算法逻辑；算法接口应返回“分数 + 版本 + 窗口质量 + 可解释拒判原因”。在数据源域和人工参考标签未完成验证前，UI 文案使用“倾向/待核对/数据不足”，不得写“确认浅睡/深睡/REM”，不得把树投票画成校准概率，也不得将候选时间累加为设备测得的睡眠时长。

### 4.2 具体技术风险

- **20 Hz 降采样可能混叠：** 旧 Python 与 Dart 特征路径按时间分箱求均值，然后以 20 Hz 计算谱特征；没有明确的抗混叠低通滤波。分箱平均有一定平滑效果，但不等价于有规格的 anti-alias filter。频谱解释和模型迁移前，需用合成带外信号及原始参考数据比较滤波器/抽取方案；改动会改变特征分布，必须重新训练或证明模型兼容，不能只在 UI 中“修图”。
- **采样率假设：** 设备是 104 Hz，而研究原始输入为 100 Hz；算法通过时间分箱对齐，但必须评估时钟抖动、缺样/计数器跳变和线性时基误差。曲线不能把补齐/插值的绘制数据回灌到模型或质量统计。
- **频谱图的含义：** 旧特征使用约 30 秒 epoch 的单窗 Hann + Goertzel/FFT 类谱功率。30 秒窗的频点间隔约 0.033 Hz，且只显示至 10 Hz；UI 应标频率、单位/归一化、窗长和算法，并标示缺样、削顶或质量拒判。若为了稳定显示改用 Welch 平均或不同窗函数，这属于算法定义变化，需与当前分类器特征分开版本化。
- **质量门不是生理校准：** 0.035g、12°/s、EOG std、削顶等是当前工程规则；来源硬件增益/安装/佩戴差异尚未用目标头带和 PSG 标定。把质量状态展示为“算法可用性检查”，不要伪装为电极阻抗/临床质量。
- **静默模型失效：** 旧 Flutter `EogSleepModel.initialize()` 捕获载入异常后将模型设为 null；Domain/UI 需将模型未加载、版本不匹配、损坏明确暴露为不可推断状态，并保留原始记录。加载前做 schema、树索引、特征维数、类别数、阈值范围、SHA-256/版本校验。
- **非概率分数：** 随机森林树叶投票比例不等于已校准概率。UI 的百分比显示容易产生确定性错觉；优先用“模型投票分数”并附定义，或只用类别/区间。任何校准要在独立验证数据做可靠性分析，不在测试集或单个用户当晚数据上调门限。
- **存储/安全：** 旧 ZIP 使用传统 ZipCrypto，恢复 CSV 可能未加密；新原生实现不应默认照搬。设计静态加密、Android Keystore 密钥生命周期、导出密码/SAF、删除和备份政策；密钥丢失的可恢复性与加密保护需明确权衡。
- **品牌升级与迁移：** applicationId 和签名决定升级通道；数据库 schema migration、旧 CSV/ZIP 导入、旧日记身份与删除关联需定义并测试。新包名若是有意更换，必须明确双安装、旧数据导入和旧版本停更路径。

## 5. UI 状态与产品导航建议

主导航建议围绕用户任务而不是图表类型：

1. **今夜：** 设备/记录状态、已记录跨度、最近质量状态、候选是否待核对、开始/停止/保存。明确展示连接但未记录、基线采集中、记录中、断连/数据不完整、保存失败等状态。
2. **记录：** session 列表、日期/状态筛选、详情；详情承载时间线、摘要、信号与统计视图、算法/设备版本和数据质量。
3. **设备：** 扫描/配对/连接、固件/电量（若协议支持）、原始信号预览、校准说明。BLE 权限请求在用户触发连接时解释并请求。
4. **日记：** 自报字段、主观感受及来源清晰标记；与算法观察并列而不混合。
5. **设置/导出：** 数据导出/删除/备份、隐私与开源许可、版本和诊断信息。

手机底部导航不宜塞入过多入口；设置、算法信息、导出可放记录详情或更多菜单。平板/横屏可以用 NavigationRail 与双栏详情，但同一数据模型与导航目的地应共用。

## 6. 图表组件规格

建立独立 `ui/components/charts`，先定义输入模型和语义，再选库。Vico 和 KoalaPlot 都是当前有维护痕迹的 Compose 图表候选（本次通过 GitHub API 检查仓库信息，Vico 为 Apache-2.0，KoalaPlot 为 MIT）；MPAndroidChart 功能成熟但起源于 View 体系，不作为 Compose-first 的默认选择。接入前核对发布版本、Android/Kotlin/Compose BOM 兼容、触摸/无障碍、许可、性能和 API 稳定度。自绘 Compose Canvas 适合少量领域特化图，需自行承担坐标轴、缩放、TalkBack、手势和测试；不要为五种常规图重复造轮子。

用户列的五类统计图加时间线/频谱可以这样定义：

| 组件 | 菲哲拉用途 | 必须表达的语义 | 设计/算法注意 |
| --- | --- | --- | --- |
| `ObservationTimeline` | 整夜 epoch 与事件时间线 | 30 秒片段：基线、倾向、待核对候选、活动、数据不足、断连；显示时间轴和起止区间 | 不命名为标准“睡眠分期图/睡眠曲线”。颜色之外还用纹理/图标/文字；缺数据画空档。点选 epoch 展开样本数、覆盖率、原因、版本和投票分数。长夜支持缩放/横向平移与全局概览。 |
| `StageProportion` | 某 session 中观测状态的构成 | 各类别所占 epoch 数和有效观测覆盖率 | 饼图只在分类互斥且总和解释明确时使用。这里的输入是实验观察标签且可能有大量 unknown/gap，优先用 100% 堆叠条或横向条，并把未知/缺样单独列出；不得把比例标成各睡眠阶段。 |
| `TrendLineChart` | EOG/IMU 指标或每 epoch 模型分数随时间变化 | 物理/统计量单位、时间窗、缺样、候选阈值/基线 | 曲线只连真实连续样本段；分数不是置信度。轴范围稳定、单位可见，支持缩放/游标值；原始波形用 min/max envelope，不做不可见的平滑。 |
| `EpochBarChart` | 每 30 秒覆盖率、运动量、样本缺口或状态计数 | 每柱窗口和统计量定义 | 柱图适合离散 epoch 或按小时汇总，不要用柱高暗示分类质量之外的含义。基线/运动/拒判状态使用分类轴与图例。 |
| `FeatureDistributionChart` | 多 session 的分数/质量指标分布或本夜分布 | 样本数、bin 宽、排除项、单位、聚合范围 | 用直方图/箱线图优先；“分布图”不是一种确定图形，需根据问题明确分箱、密度或分位数。显示 n 和缺失量；小样本避免平滑 KDE 造成虚假形状，严禁把少量 epoch 当人群泛化。 |
| `EogSpectrumChart` | 选择 epoch 的 EOG 频谱检查 | Hz 横轴、功率/归一化纵轴、窗函数、时长、有效频点 | 质量拒判时明确遮罩或提示，不把零填充画成低能量。展示 0.1–10 Hz 研究范围与频率分辨率；研究特征谱和供用户看的平滑谱可以是两种独立模式，不得混为训练输入。 |

### 6.1 值得一并新增的组件

- `ConnectionStatusBar`：设备连接状态、采集状态、最后样本时间和缺包提示；断连态提供可执行的重连操作。
- `SignalQualitySummary`：EOG 波动/削顶、IMU 活动、样本覆盖率及判定原因；不能虚构硬件未提供的接触阻抗。
- `SessionMetricRow`：记录跨度、收到样本数、数据覆盖率、候选窗口数、断连时长；把“记录时长”与“自报睡眠时长”分开。
- `EpochDetailSheet`：epoch 类型、开始/结束、样本缺口、IMU、模型版本/分数、拒判和规则理由；图表点击与无障碍列表语义一致。
- `DataGapOverlay`：跨图一致的断连/时间戳不可信区段。
- `ChartLegend` / `ChartRangeSelector` / `ChartCursor` / `ChartEmptyState`：统一系列显隐、24 小时/整夜等视窗、触摸读数、无数据/质量拒判状态。
- `EvidenceNotice`：显示“研究模型/未经头带验证”的版本化说明，不能只放在深层关于页。

### 6.2 图表可访问性与性能

- 图例不能只靠颜色；至少有文字/图案，读数可被 TalkBack 访问，支持大字体和色觉差异。
- 触摸游标、缩放和平移应有大 hit target；非交互预览需有等价的 epoch 列表/摘要，不强迫用户读图。
- 固定图表最小高度/宽高比；动态标签、空态和旋转不应让布局跳动。遵循系统字体缩放，横屏/窄屏均测试。
- Compose Canvas 绘制中不分配大量对象、不做数据库/FFT/抽稀计算；预处理在 Repository/UseCase/后台线程。使用 `drawWithCache`/缓存路径、stable immutable state 和窗口化数据；高频 BLE 状态不要使整个导航树重组。
- 以整夜最大数据量在中低端设备测帧耗时、内存、缩放响应和 Compose recomposition；性能目标结合刷新率设定，优先保证滚动/触摸稳定而非动画装饰。

## 7. 图表依赖选择原则

- 候选一：**Vico**，Compose-first/Compose Multiplatform 图表库，仓库许可 Apache-2.0；优点是常用序列图与 Compose 集成，适合 line/bar 起步。需核对当前版本是否覆盖 pie、histogram、触摸辅助线及所需无障碍语义；不要假设一库包揽所有图形。
- 候选二：**KoalaPlot**，Compose Multiplatform、MIT，仓库文档列出 pie/donut、line、vertical bar 等。验证其数据量、手势、动态轴与维护活跃度，以及与当前 Android/Kotlin 版本的兼容。
- **MPAndroidChart**：Apache-2.0，功能包括 line/bar/pie 等，但主要是传统 Android View 图表。仅当缺失关键能力且已确认 ComposeView/interop 的测试、触摸和无障碍成本可接受时引入。
- 选择前做 1 个真实的长夜时间线原型和 1 个频谱/游标原型，测试留存状态、缩放、缺失区间、TalkBack、大字及 8 小时数据量；比较后锁定一个主库，特殊的 timeline/频谱可用 Canvas 组合，不要 UI 中混合多套库风格。
- 不建议仅为“统计图种类齐全”引入依赖。饼图和分布图没有真实决策价值时宁可不用；每个图要对应用户问题和数据证据。

## 8. 建议实施顺序

### Phase 0：产品与协议决策

确认 applicationId、签名、最低 SDK、旧数据迁移/旧版本升级、隐私备份策略；从固件代码固化 UUID、样本字段、小端编码、采样频率、计数器回卷、马达/电源命令并建立协议版本测试。

### Phase 1：Compose 应用壳与状态约定

替换模板路由/占位内容/FAB；确定品牌主题、导航和窗口自适应。建立 `UiState + UiAction + ViewModel + Repository` 单向链路，加入加载/空/错误/权限/断线状态；做 Compose navigation/UI test。

### Phase 2：BLE、后台采集、数据库先于漂亮图表

实现协议 parser 单元测试，完成连接状态机、运行时权限、采集服务、Room session/sample/epoch 持久化、断线/进程恢复语义、导出与数据删除。先通过长时间模拟包和真机短测，确认不依赖 UI 生命周期，再做记录列表和 session 详情。

### Phase 3：只实现高价值可视化

先做连接/质量摘要和 `ObservationTimeline`；再做 EOG/IMU 趋势与 epoch 详情；之后加阶段/状态比例（优先条形）、按小时汇总、样本分布与频谱诊断。对每个组件定义数据类、单位、无数据态和 UI 测试。不要先一次性创建六张空壳图。

### Phase 4：算法与跨人验证

把特征提取移至可单测的 Kotlin Domain/算法模块，保持版本化 golden vectors；测试采样抖动、104→20Hz、丢包、截幅、低变异、计数器回卷、设备重启和时钟跳变。建立本硬件与同步 PSG 的独立数据计划，按受试者隔离训练/验证/测试；在验证完成前维持“研究/观察”标签和自动反馈关闭。

### Phase 5：产品验收与发布

覆盖 Android 版本/厂商后台、8 小时以上采集、内存/磁盘/电量/温升、断连重连、崩溃恢复、升级/数据库迁移、加密导出/删除、权限撤销和备份策略。发布签名必须与目标升级通道兼容；销售或医疗用途声称另行完成适用验证和合规评估。

## 9. 验收清单

- [ ] 旧包与新包的 applicationId、签名和用户升级/迁移决定已书面确认。
- [ ] BLE parser 覆盖正常包、坏包、短包、counter wrap、drop、重连和异常数据单测。
- [ ] 录制在屏幕关闭/切后台时继续，UI 离开/旋转不停止会话；进程或连接丢失时如实标记中断。
- [ ] 原始数据、质量 epoch、算法版本与导出报告可相互追溯；图表重开后从持久层一致恢复。
- [ ] 所有图区分空数据、低质量、缺样、断连、模型不可用；图表抽稀不改变分析/统计源数据。
- [ ] 轴单位、时间区、窗口长度、样本数、缺失量、模型状态清楚可见；候选不是确认睡眠、投票分数不是准确率/置信度。
- [ ] 小屏、大字体、横屏/平板、深浅色、TalkBack、色觉差异和高频刷新均有自动/人工测试。
- [ ] 在目标手机运行整夜和故障恢复；帧时间、内存、写入积压、温升、电量、文件大小有测量数据。
- [ ] Room migration、备份排除、加密密钥生命周期、SAF 导出、删除与恢复路径已测试。
- [ ] 关键 Compose 页面和 ViewModel 分别有状态/动作测试；图表有极值、单点、空区间、大数据集和触摸读取测试。

## 10. 参考资料

**Android 官方**

- [Compose UI architecture](https://developer.android.com/develop/ui/compose/architecture)：单向数据流、状态持有和 UI 职责。
- [Where to hoist state](https://developer.android.com/develop/ui/compose/state-hoisting)：状态提升位置和可复用组件边界。
- [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations)：分层、Repository、ViewModel、Flow 与测试建议。
- [Compose graphics overview](https://developer.android.com/develop/ui/compose/graphics/draw/overview)：Canvas、DrawScope、`drawWithCache`。
- [BLE background communication](https://developer.android.com/develop/connectivity/bluetooth/ble/background)：后台扫描/连接及进程生命周期限制。
- [Slow rendering](https://developer.android.com/topic/performance/vitals/render)：帧耗时与卡顿的性能背景。

**图表库**

- [Vico](https://github.com/patrykandpatrick/vico)：Compose-first 图表库，Apache-2.0。
- [KoalaPlot](https://github.com/KoalaPlot/koalaplot-core)：Compose Multiplatform 图表库，MIT。
- [MPAndroidChart](https://github.com/PhilJay/MPAndroidChart)：Android View 图表库，Apache-2.0。

**算法/信号背景**

- [Sleep-EDF Expanded (PhysioNet)](https://physionet.org/content/sleep-edfx/1.0.0/)：原模型使用的公开 PSG/EOG 数据源之一；与菲哲拉头带采集链路不同。
- [van Gorp et al., 2024, Single-channel EOG sleep staging on a heterogeneous cohort](https://doi.org/10.1088/1361-6579/ad4251)：单通道 EOG 睡眠分期研究；说明单通道 EOG 是可研究方向，不验证当前模型/硬件。
- [SciPy `decimate`](https://docs.scipy.org/doc/scipy/reference/generated/scipy.signal.decimate.html)：抽取前抗混叠滤波定义。
- [SciPy Welch PSD](https://docs.scipy.org/doc/scipy/reference/generated/scipy.signal.welch.html)：平均周期图功率谱密度定义。
- [scikit-learn probability calibration](https://scikit-learn.org/stable/modules/calibration.html)：分类分数的概率校准概念；当前模型投票不应直接称置信度。

---

本文中的仓库版本/许可信息于 2026-11-03 通过 PowerShell `curl`/GitHub API 和官方页面核对；具体依赖版本上线前仍须锁定 tag/release 并跑兼容性构建。建议文档随产品版本和模型/协议 schema 更新，不把研究指标复制为产品承诺。
