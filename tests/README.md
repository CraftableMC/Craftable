# 测试入口与职责

本地回归和显式诊断统一使用 [run.ps1](run.ps1)。测试要防住的错误、断言取舍及证据边界见[风险覆盖](coverage.md)；数据包重载操作见[夹具说明](datapacks/README.md)。

## 1. 运行入口

要求 Java 21。在仓库根目录用 PowerShell 运行：

```powershell
.\tests\run.ps1 -SelfTest                         # 入口判定与命名自检，不启动游戏
.\tests\run.ps1 -Group all -Offline               # 完整常规回归
.\tests\run.ps1 -Group baseline -Offline          # 单元测试、GameTest 和构建
.\tests\run.ps1 -Group inventory -Offline         # 背包、原版配方书与制作
.\tests\run.ps1 -Group planning -Offline          # 规划、数量/MAX、设置
.\tests\run.ps1 -Group settings -Offline          # 仅设置
.\tests\run.ps1 -Group knowledge -Offline         # 专服缓存预热与独立进程重启
.\tests\run.ps1 -Group diagnostic -Diagnostic lease -Offline
.\tests\run.ps1 -Group diagnostic -Diagnostic timing -Offline
.\tests\run.ps1 -Group all -Offline -DryRun       # 只查看执行计划
```

省略 `-Group` 时运行 `unit`。只有本机已缓存依赖和游戏资产时才加 `-Offline`；首次准备环境请省略。各组串行执行，避免多个进程同时写入 `run/client` 或 `run/server`。客户端组会启动游戏、创建隔离测试世界并自动退出。

`all` 包含 baseline、inventory、planning，以及 knowledge 的 warmup 和 restart 两个进程，不运行可选诊断。planning 包含完整设置组及延期 MAX；settings 只运行设置，不能报告为完整 planning 通过。diagnostic 必须指定 lease 或 timing，其他组不接受 `-Diagnostic`。`-DryRun` 只打印计划，不执行测试。

CI 沿用 Gradle 标准 `build` 生命周期，当前[工作流](../.github/workflows/build.yml)执行 `./gradlew build`，包含标准 JUnit 测试。真实客户端、GameTest 和专服缓存专项由本地入口运行，CI 构建通过不代表这些专项已全部执行。

## 2. 分组与覆盖

下表中的 Gradle 参数用于说明脚本如何分发任务。本地运行仍从 `tests/run.ps1` 选择分组。脚本关闭 configuration cache；含 JUnit 的组使用 `cleanTest` 和 `--no-build-cache`，确保实际执行测试。

| 组 | Gradle 任务或参数 | 主要覆盖 | 证据边界 |
| --- | --- | --- | --- |
| unit | `cleanTest test` | 配置与模式、手势、预算、网络帧、快照接收、知识文件、翻译键/参数及纯 UI 几何 | 不验证真实菜单渲染、世界事务或局域网 |
| server | `runGameTestServer` | 扫描与菜单资源、递归规划、余料/容量、事务回滚、见证攻击、规则与预算、缓存 | 不验证客户端帧或多设备延迟 |
| inventory | `runClient -PcraftableClientSuite=inventory` | 原版配方书、筛选、C 制作、实体工作台、菜单材料移动、模式切换、药水效果、迟到确认回复隔离 | 不代表所有装备操作或真实两机体验 |
| planning | `runClient -PcraftableClientSuite=planning` | 目录与调度、未知结论、Shift+C、候选/材质、数量/MAX、实际成本、见证确认、部分合成、双语界面和设置 | 不代表任意模组目录、硬件或网络条件 |
| settings | `runClient -PcraftableClientSuite=settings` | 导航、草稿与持久化、权威规则、白名单、专业页、双语排版和清理恢复 | 不包含延期 MAX 或完整 planning |
| diagnostic + lease | planning 加 `-PcraftableClientDiagnostic=lease` | 确认按钮的租约过期、同值续期和只读 MAX 等待 | 在租约场景后结束，不能作为 planning 全量证据 |
| diagnostic + timing | planning 加 `-PcraftableClientDiagnostic=timing` | 完整路线中的服务端审阅阶段计时 | 计时与日志有开销，不能作为无诊断性能样本 |
| knowledge | 两次 `runServer`，分别指定 `-PcraftableKnowledgePhase=warmup`、`restart` | 真实保存、独立 JVM 载入、首次缓存命中、实际重载及专服类加载 | 不代表不同硬件或全部 I/O 故障 |
| baseline | `cleanTest test runGameTestServer build` | 无图形回归及 JAR 构建 | 不包含真实客户端或人工验收 |

knowledge 的 warmup 进程必须实际保存有效缓存。restart 启动前核对该文件的 SHA-256；进程中须在首次查询前已载入关系，首次查询命中且没有新增构建。每个进程还要在 PASS 后写出非空缓存。缓存失配可以被正确拒绝并重建，文件存在本身不能证明缓存有效。

`LeaseReadinessScenario` 在相同资源身份下检查租约过期时禁用确认、同值续期后恢复，并覆盖只读 MAX 等待。真实 MAX 的排队、取消和非单调容量由 planning 的数量场景验证。MAX 检查同时保护新鲜授权的启用正例和当前 scope 内过期授权的禁用反例。

## 3. 夹具职责

| 位置或入口 | 职责 |
| --- | --- |
| `src/test/java/org/craftablemc/craftable/` | JUnit 单元测试；配置、手势、状态生命周期、预算、编解码、快照、缓存及图形几何 |
| `src/main/java/org/craftablemc/craftable/` 下的 GameTest | 真实世界、菜单、权限、规划与事务验证，由 NeoForge 发现 |
| `src/integrationTest/java/org/craftablemc/craftable/` | 真实客户端与专服缓存夹具，Gradle 源集为 `integrationTest`，不进入发布 JAR |
| `execution/DirectCraftingGameTests`、`menu/InventoryMenuGameTests`、`execution/MenuResourceGameTests` | 直接合成、真实菜单网格/光标/外部材料、结果格排除、模式切换与返还 |
| `execution/PlanningGameTests`、`TransactionGameTests`、`RandomGraphGameTests` | 递归与共享批次、需求缺口、守恒、容量、回滚及随机图 |
| `execution/DetailsGameTests`、`InteractionGameTests`、`PreviewGameTests`、`ContinuationGameTests` | 详情审阅、显式部分意图、单次令牌、重放/重入、规则与材料变化后的拒绝、同核续算 |
| `execution/SearchRegressionGameTests`、`AdmissionGameTests`、`CapacityDiagnosticGameTests` | 预算与准入、完整否定/未知边界、容量失败及合法替代路线 |
| `execution/BulkPlanningGameTests`、`WitnessAccountingGameTests`、`WitnessReviewGameTests` | 多批次、保守材料证书、真实来源与贡献、见证重放及共享预算 |
| `execution/StepLimitGameTests`、`WorldRulesGameTests`、`DirectPreparationGameTests` | 当前步数、四态部分规则、世界/个人交集、单白名单、真实规则同步及确认失效 |
| `planner/MissingIdentityGameTests`、`network/BrowseTransferGameTests`、`recipe/KnowledgeGameTests` | 材料身份、实际快照发送与补传、静态关系和缓存边界 |
| `execution/PlanningTestSupport`、`TransactionTestSupport` | 包内玩家/会话清理、搜索与资源快照辅助，供 GameTest 共用 |
| `client/InventoryClientSmoke` | inventory 调度；`RepeatCraftScenario`、`MenuMoveScenario` 和 `LateConfirmationScenario` 负责连续制作、材料移动及迟到回复 |
| `client/PlanningClientSmoke` | planning 调度；按具名步骤等待就绪、分派场景、处理超时与清理 |
| `client/BrowsingScenario`、`BrowseSchedulingProbe`、`DiagnosticLifecycleScenario` | 浏览规则同步、任务公平性/提升、结论与诊断隔离、未知终止 |
| `client/PlanningReviewScenario`、`LeaseReadinessScenario`、`PersonalPreferenceLifecycleScenario` | 候选与权威审阅、完整/部分确认、租约就绪、个人偏好变化后的意图和令牌失效 |
| `client/PlanningQuantityScenario`、`DetailQuantityScenario`、`BulkQuantityScenario`、`DeferredMaximumProjectionScenario` | 数量/MAX、精确成本、容量非单调、排队取消、延期投影与实际大批交付 |
| `client/OutputVariantScenario`、`MixedMaterialScenario`、`GraphPresentationScenario`、`GraphRoutingScenario` | 最终材质、按钮显式颜色与真实帧像素、混合投入、同类显示合并、选择路径、连线几何及折叠恢复 |
| `client/PlanningPresentationScenario`、`PlanningClientFixture`、`ClientTestRun` | GUI 缩放、语言重载、刷新样本、输入/截图辅助及本次运行身份 |
| `client/SettingsScenario`、`SettingsStep`、`SettingsScenarioState` | 设置场景调度、具名等待步骤和一份会话状态 |
| `client/SettingsNavigationChecks`、`SettingsPersistenceChecks`、`SettingsRulesChecks` | 导航与末行布局、草稿/落盘/专业页互通、权威来源/序号/只读同步 |
| `client/SettingsWhitelistChecks`、`SettingsProfessionalChecks`、`SettingsProfessionalGuardChecks` | 名单父子草稿、搜索/二态、目录状态、精确 ID、Undo/Redo/Reset 和上下文失效拒写 |
| `client/SettingsPresentationChecks`、`SettingsCapture`、`SettingsFixture`、`SettingsUiAccess` | 真实字体与边界、渲染等待、截图、成功/异常恢复及窄范围 UI 适配 |
| `client/SnapshotResyncScenario` | 在真实请求、轮询、头/分块与解码链上注入停滞，验证有界恢复 |
| `recipe/ClientKnowledgeProbe`、`KnowledgeServerSmoke` | 客户端目录样本与 knowledge 的预热、独立重启、重载和停服 |

表中 GameTest 包路径相对于 `src/main/java/org/craftablemc/craftable/`；客户端和专服夹具路径相对于 `src/integrationTest/java/org/craftablemc/craftable/`。精确失败风险和各夹具不能证明的范围见[覆盖核对](coverage.md)。

## 4. 结果与证据

任意一步失败立即停止。客户端使用带本次 UUID 的 `CLIENT_TEST START/PASS suite=… diagnostic=… run=…`；Gradle 与脚本检查身份、作用域、顺序、唯一性及失败优先。settings 使用 `CLIENT_SETTINGS PASS`，诊断使用 `CLIENT_DIAGNOSTIC PASS`，二者不得同时声称 `PLANNING_SMOKE PASS`。GameTest 和 knowledge 还须出现各自的完成标记。

运行前会归档已有 `latest.log`，随后只检查本次新日志。进程退出 0、缓存报告或单个 PASS 字样都不足以判定成功。

| 产物 | 位置 |
| --- | --- |
| 每步控制台与游戏日志 | `run/verification/<时间>-<组>/` |
| JUnit XML / HTML | `build/test-results/test/` / `build/reports/tests/test/` |
| 客户端截图 | `run/client/screenshots/` |
| knowledge 隔离存档 | `run/knowledgeServerSmoke/craftable-knowledge-smoke/` |

这些生成物被 Git 忽略。截图会覆盖同名文件，需要留证时与同批日志一起归档。故障注入产生的预期 ERROR 应结合测试名和最终断言判断。

图形场景的截图包括 `graph-sibling-*`、`graph-sibling-routing-*`、`detail-refresh-tooltip.png`、`detail-quantity-three.png`，设置截图使用 `settings-*`。它们辅助检查材料数量、布局和提示；文件存在不等于画面正确，也不代替交互或多设备验收。

## 5. 验证边界

测试构造显式给出当前策略、步数、序号和见证参数。配置回归覆盖当前注册 spec 的默认值、非法值、数值/整表边界、显式空白名单和个人×世界 16 种模式组合。诊断缓存使用 `environment.diagnosticCacheTicks`，默认 5、范围 0–20，只影响只读诊断。

冻结时钟能验证功能和预算检查点，不能证明任意硬件都在给定毫秒数内完成。受控快照停滞、页面身份失效或异常清理能验证对应反例，不能直接代替真实 LAN 切换、多设备网络、文件监听或掉落实体体验。

Pre-M5 的人工与自动结果见[验收记录](../docs/development/18-pre-m5-acceptance.md)，调用链与生命周期证据见[代码质量审计](../docs/development/17-production-behavior-audit.md)。报告结果时写明本次实际运行的组、构建身份、失败和未覆盖范围。
