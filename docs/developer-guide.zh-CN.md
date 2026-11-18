# 开发指南

[English](developer-guide.en.md) · [文档导航](README.md) · [用户指南](user-guide.zh-CN.md) · [开发资料索引](development/README.md)

本页帮助贡献者建立环境、找到代码职责，并在修改时守住安全边界。详细设计、实现细节及历史验收分别链接到开发资料。

## 1. 环境与构建

- Java 21 JDK。
- 当前目标是 Minecraft 1.21.1、NeoForge 21.1.235；版本以 [gradle.properties](../gradle.properties) 为准。
- 使用仓库自带的 Gradle Wrapper，无须另外安装 Gradle。Wrapper 版本由 [配置文件](../gradle/wrapper/gradle-wrapper.properties) 固定；工具链、依赖和源集由 [build.gradle](../build.gradle) 定义。

在仓库根目录构建：

```text
# Windows PowerShell
.\gradlew.bat build

# Linux / macOS
./gradlew build
```

产物位于 `build/libs/`。首次准备依赖和游戏资产需要网络；只有缓存齐全时才适合离线运行。不要把构建产物、开发世界或缓存提交到源码树。

IDE 中以 Gradle 项目导入仓库，并使用 Java 21。

## 2. 源码布局与模块职责

| 位置 | 负责内容 | 边界 |
| --- | --- | --- |
| `src/main/java/` | 模组实现及沿 NeoForge 发现的 GameTest | 不反向依赖 integrationTest；专服不加载客户端渲染类 |
| `src/main/resources/` | 模组元数据、语言和窄范围 Mixin 配置 | 不把整个生产根包声明为 Mixin 包 |
| `src/test/` | JUnit 单元测试 | 不以纯模型检查替代真实菜单、事务或联网验收 |
| `src/integrationTest/` | 真实客户端与专服缓存夹具 | 独立源集，不进入发布 JAR |
| `tests/` | 回归入口、覆盖边界及数据包夹具说明 | 测试命令与分组只在这里维护 |

主要代码入口如下。完整关系见[技术架构](development/02-architecture.md)和[代码质量审计](development/17-production-behavior-audit.md)。

| 职责 | 入口 | 状态所有权与线程 |
| --- | --- | --- |
| 环境发现 | [EnvironmentScanner](../src/main/java/org/craftablemc/craftable/environment/EnvironmentScanner.java) | 服务端游戏线程发现已加载、可用且有权限的来源；活端点不交给客户端 |
| 配方知识 | [CraftingRecipes](../src/main/java/org/craftablemc/craftable/recipe/CraftingRecipes.java) | 两端各自拥有单代静态目录，仅服务端持久化静态关系；异步知识 I/O 只接收不可变快照，并拒绝旧代写回 |
| 同核规划 | [CraftSearch](../src/main/java/org/craftablemc/craftable/planner/CraftSearch.java)、[ResourceLedger](../src/main/java/org/craftablemc/craftable/planner/ResourceLedger.java) | 在冻结输入上计算，守恒输入与产物；核心不引用客户端类 |
| 客户端计算与显示 | [ClientBrowsePlanner](../src/main/java/org/craftablemc/craftable/client/recipebook/ClientBrowsePlanner.java)、[ClientRecipeStatusStore](../src/main/java/org/craftablemc/craftable/client/recipebook/ClientRecipeStatusStore.java) | 客户端线程拥有任务、累计额度及动态结果；缓存和图投影不是执行授权 |
| 权威审阅与提交 | [CraftingService](../src/main/java/org/craftablemc/craftable/execution/CraftingService.java)、[CraftWitnessValidator](../src/main/java/org/craftablemc/craftable/execution/CraftWitnessValidator.java)、[CraftingTransaction](../src/main/java/org/craftablemc/craftable/execution/CraftingTransaction.java) | 服务端游戏线程新鲜复验，所有实际扣料和交付收敛到唯一事务 |
| 短期会话与网络 | [CraftingSessions](../src/main/java/org/craftablemc/craftable/execution/CraftingSessions.java)、[CraftablePayloadHandlers](../src/main/java/org/craftablemc/craftable/network/CraftablePayloadHandlers.java) | 分别拥有序号/令牌/来源绑定和网络交接；世界读写回到服务端游戏线程 |
| 配置与界面 | [ConfigSettings](../src/main/java/org/craftablemc/craftable/config/ConfigSettings.java)、[CraftableSettingsScreen](../src/main/java/org/craftablemc/craftable/client/CraftableSettingsScreen.java) | 注册 spec 是唯一真值；普通界面只有有界草稿，远端规则只来自当前权威快照 |

<a id="identity"></a>

### 组织身份与兼容边界

项目以 CraftableMC 名义维护，Maven group 为 `org.craftablemc`，Java 包根为 `org.craftablemc.craftable`。模组 ID 和资源命名空间仍为 `craftable`，模组元数据名称为 Craftable；游戏内功能名为“配方书”（英文 Recipe Book）。

两个 Mixin 配置分别限制在 `client.mixin`、`menu.mixin`。更改包结构时，一起检查主类、Mixin 声明、反射字符串、测试发现和源码链接；用干净构建后的 JAR 和 IDE 输出确认没有旧类残留。

开发构建不保留旧 Java 包、配置格式或调用签名的兼容层；如果已经依赖旧包名，要更新导入后重新编译。改动结构时同步更新实际调用与当前测试，不以默认参数掩盖缺少的规则输入。Java 包调整不应顺带改动模组 ID、存档或配方 ID。

## 3. 修改时必须保留的安全合同

- 客户端只提交意图和只读见证，不能指定世界写入、槽位修改或授权产量。服务端重新检查真实来源、权限、配方、材料新鲜度、余料与联合容量；本地可行结果不能替代这些检查。
- 完整成功、充分否定和未知必须分开。截断、预算耗尽或客户端的失败声明不能证明缺料，也不能授权部分执行。最大制作数量未证明时只是下界，不是执行上限授权。
- 普通单次制作不会失败后自动消耗一部分材料。部分制作须有明确意图，并经过服务端规则与个人收紧的交集；完整见证路径不能通过降级或省略字段绕过验证。
- 审阅令牌是一次性的短期判据，不是材料预留或跨时间可执行计划。审阅时与提交前的复验分别保护不同时间边界，不能为了去重合并掉。
- 唯一事务同步核对、提取和发布。失败补偿本事务的输入及掉落实体；提交后的通知/统计失败不能让已交付物再次获得退款。不承诺回滚任意第三方钩子的外部副作用。
- 时间、状态、步骤、字节和内存限制互相独立。提高可表示步数不能扩大计算预算，暂停/续租/取消重开不能重置累计消耗；合作式片额不是抢占式硬实时保证。
- 配方书、详情图与材料合并只是显示投影，不得改变真实需求路径、精确组件、来源或消耗。没有明确执行契约的第三方来源、机器或便携内容不自动启用。

这些约束的依据见[规划与执行语义](development/03-planner-semantics.md)、[客户端规划合同](development/14-m4-implementation.md#browsing)及[配置合同](development/04-compatibility-and-config.md)。编号资料包含现行约束与带范围标记的历史，不应统称为过时文档。

## 4. 配置、取消与失效

普通页和专业页必须共用已注册配置的定义、默认值、校验与持久化(简单来说，他们必须是同一配置的不同表现形式)。世界部分合成模式和唯一配方 ID 白名单由服务端拥有；个人模式只能收紧，并引用同一张世界名单，不允许复制名单或增加隐式许可。

普通页完成时校验并只保存已改项，取消只放弃其草稿，不撤销专业页已保存的更改。
世界规则写入必须在实际写点复查当前单人、未开放 LAN 和 SERVER 已加载等上下文；控件变灰不能替代保存、Undo/Redo/Reset 或延迟回调的门禁。专业页保持原生即时注册值与撤销语义。

远端未同步时不能使用本地 SERVER 值冒充权威规则(听上去像废话,但是决定不允许这么干)。
租约与浏览快照须来自同一次捕获的规则值；静态知识与玩家资源、许可和执行令牌分开。
重载、规则/材料/容量改变、换菜单、断线或换世界时，各状态所有者负责清理自己的派生证据。菜单变化只清派生状态。取消会丢弃旧排队意图与迟到回复，但不追溯撤销已发往服务端的提交。

控件身份、延迟滑块提交、界面排版和保存门禁的细节见[设置界面维护](development/04-compatibility-and-config.md#settings-ui-maintenance)。

## 5. 验证与问题定位

本地自动回归与显式诊断的唯一推荐入口是 `tests/run.ps1`。分组、参数、真实执行证据和本地日志位置在[测试说明](../tests/README.md)维护；断言要保护的风险见[覆盖边界](../tests/coverage.md)，数据包操作见[夹具说明](../tests/datapacks/README.md)。

CI 使用标准 Gradle `build` 生命周期，包含标准单元测试，但不隐式启动真实客户端、GameTest 或专服缓存专项。缓存命中或构建成功，不等于所有专项已实际执行；过滤测试和诊断不能报告成全量通过。

按改动影响选择回归。例如，改整链步数不能只测配置读写，还要验证客户端与服务端规划、见证、显示投影、解码和内存限制。计时诊断有额外开销，不能用带诊断通过代替关闭诊断后的常规结果；性能改动要区分首次冷路径和已有结果复用。

提交修改时说明实际运行的组、结果与未覆盖范围。发现真正行为缺陷时，将它与等价重构分开，并补能失败的反例。构建身份、失败和人工结果按各批实际范围记入对应的验收文档，可参考 [Pre-M5 验收](development/18-pre-m5-acceptance.md)。`run/`、`logs/` 中的本地证据不默认是仓库附件，也不得未经授权上传玩家存档或私有信息。

## 6. 贡献与文档维护

- 修改前阅读相关设计依据，保持职责与状态所有者清楚；不为去重合并不同时间边界的安全复验，不为测试方便暴露生产私有入口或建立通用大框架。
- 保留他人工作树改动。事件订阅、Mixin、网络处理器等框架入口不能仅因搜索不到普通调用就删除。死代码清理要有真实调用链和消费者证据。
- 规格和现行合同原位更新；计划不预填成功，验收保留真实失败与构建身份，ADR/历史保留当时语境。新能力先落实真实执行契约，不把后续设计写成已实现。
- 同步维护中英文公开指南；测试命令只改测试索引，详细开发资料的职责和现行/历史分类由[开发资料索引](development/README.md)说明，不得把根 README 重新扩成档案目录。
- 代码、素材和依赖须有适用授权并保留声明。项目使用定制的 [CraftableMC Community License](../LICENSE)（[中文译文](../LICENSE.zh-CN)）。

<a id="code-comments"></a>

### 代码与测试注释

- 重要注释在同一处用中英文表达同一含义，解释职责、副作用、线程、所有权、预算、取消与失败处理。方法内部的关键说明也适用，不是只给类头加中文；明显代码和普通 getter 不必逐行解释。
- 测试注释重点解释为什么选这个反例、哪种错误会使它失败，以及使用的输入、时钟或隔离条件不能证明什么。简单输入输出断言不必双写，英文方法名、失败消息及稳定日志标记不为双语化改名。
- 如果改动仅限注释，以本次开始时的工作树为基线，确认非注释的代码和字面量没有变化，不把此前未提交的行为改动算作本次的一部分。


