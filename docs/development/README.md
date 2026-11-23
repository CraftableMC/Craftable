# 开发资料索引

>特别声明：本系列文档主要是 [@lewe189](https://github.com/lewe189) 记录开发过程中的开发记录，可能存在记录不全或记录懒散或语言晦涩难懂(可能省略了某些东西导致其他人不太看得明白)或记录不规范或使用一些口头语等问题，同时方便起见只提供中文，还请多多包涵。另外，代码和注释里也一样。

[文档导航](../README.md) · [模组介绍](../../README.md) · [中文介绍](../../README.zh-CN.md)

本目录收纳设计依据、实施计划、验收证据、实现维护参考和架构决策。
文件名中的阶段或编号用于追溯，不能单凭名称判断其中约束是否仍有效。


长期公开指南另行维护：
[中文用户指南](../user-guide.zh-CN.md) / [English User Guide](../user-guide.en.md)，
[中文开发指南](../developer-guide.zh-CN.md) / [English Developer Guide](../developer-guide.en.md)。

本页负责开发资料的详细索引，不重复安装、操作或构建教程。

## 阅读与适用边界

- 新开发者先读公开开发指南，再按问题进入下面的设计依据和实现维护参考。
- M4 文档仍保留有效的资源、规划、事务和授权边界。协议 9、固定 128 步和旧部分准备规则属于当时的构建。现行设置与部分合成规则见 [Pre-M5 设置](16-pre-m5-transition.md#settings)和[部分合成规则](16-pre-m5-transition.md#partial-policy)。
- 规格、计划与证据各有职责：规格说明约束，计划说明范围和顺序，验收记录说明实际执行过什么。设计冻结、编译成功、自动回归和人工通过不能互相替代。
- 文档与代码冲突时应核对具体规则、实现和对应验收来源。
- `run/verification/`、`logs/` 等路径多为未上传的本机原始证据；各批次的日期、哈希、失败及未执行范围保留当时语境，不能当作仓库可下载附件或新构建的通过证明。

## 现行设计依据

这些资料仍用于理解当前约束或明确的后续边界；其中带阶段语境的部分须结合上面的适用规则阅读。

| 资料 | 职责与阅读重点 |
| --- | --- |
| [00 产品定义](00-product-definition.md) | 产品原则、纯原版优先和跨阶段范围；不是全部规划能力的支持清单。 |
| [02 技术架构](02-architecture.md) | 同一规划/事务核心、模块职责、线程与状态所有权、授权和新鲜复验。 |
| [03 规划与执行语义](03-planner-semantics.md) | 递归、多产出、循环、余料和部分准备的跨阶段原则；具体支持范围结合现行补充。 |
| [04 兼容性与配置](04-compatibility-and-config.md) | 当前配置、来源与同步、兼容等级及未实现功能的边界。 |
| [05 测试与风险](05-testing-and-risks.md) | 测试策略、风险分类和后续规模观测；实际入口仍在 tests/。 |
| [14 M4 实施说明](14-m4-implementation.md) | 有界递归、数量/部分意图、唯一事务、共享知识、客户端浏览和完整见证；按最终职责阅读，旧参数不替代 Pre-M5。 |

## 当前计划与阶段收尾

| 资料 | 当前用途 |
| --- | --- |
| [01 实施路线图](01-roadmap.md) | 当前实施顺序、已完成阶段和后续范围。 |
| [19 M5 实施计划](19-m5-implementation-plan.md) | 客户端静态目录预热、原版工作站、计时订单及配方范围。 |
| [20 M5 验收与测试记录](20-m5-acceptance.md) | 验收条目、已执行的验证、当前阻塞和取舍。 |
| [17 代码质量审计](17-production-behavior-audit.md) | Pre-M5 收尾时的行为链审计、等价清理、独立缺陷和针对性验证。 |

Pre-M5 于 2026-10-28 结束。M5 从静态配方预热开始实施，M6 的配方书改进和 R1 的资源端点仍处于设计阶段。

## 历史阶段实施与验收

以下文件负责说明当时的计划、实现和证据，不是当前待办清单。保留历史并不表示其中所有工程原则都失效；当前规则变动由现行依据和明确的后续补充说明。

| 阶段 | 实施资料 | 验收及证据 |
| --- | --- | --- |
| M0 原型 | [06 原型实现说明](06-m0-prototype-notes.md) | [07 原型验收](07-m0-acceptance.md) |
| M1 环境与协议 | [08 实施计划](08-m1-implementation-plan.md) | [09 验收报告](09-m1-acceptance.md) |
| M2 单步事务 | [10 实施计划](10-m2-implementation-plan.md) | [11 验收报告](11-m2-acceptance.md) |
| M3 原版配方书与菜单 | [12 实施计划](12-m3-implementation-plan.md) | [13 验收报告](13-m3-acceptance.md) |
| M4 有界规划与整链事务 | [14 实施说明](14-m4-implementation.md) | [15 验收记录](15-m4-acceptance.md) |
| Pre-M5 设置与体验调整 | [16 实施说明](16-pre-m5-transition.md) | [18 验收记录](18-pre-m5-acceptance.md) |

## 架构决策记录（ADR）

ADR 解释决策背景、备选方案与安全边界。是否被替代，应看明确的后续决策及当前合同，不能仅按文件日期判断过时。

| 决策 | 内容 |
| --- | --- |
| [ADR-0001](adr/0001-vanilla-recipe-book-frontend.md) | 使用原版配方书作为默认前端。 |
| [ADR-0002](adr/0002-m1-environment-snapshot-contract.md) | 环境快照的短期服务端授权合同。 |
| [ADR-0003](adr/0003-vanilla-feedback-and-settings-surfaces.md) | 原版式反馈、设置入口与个人/世界规则边界。 |
| [ADR-0004](adr/0004-m2-compensated-single-craft-transaction.md) | 单步补偿事务与提交后通知。 |
| [ADR-0005](adr/0005-m3-recipe-book-and-menu.md) | 配方书投影、真实菜单及索引安全。 |
| [ADR-0006](adr/0006-m4-bounded-planning-and-chain-transaction.md) | 有界规划和整链守恒事务。 |
| [ADR-0007](adr/0007-shared-recipe-knowledge.md) | 共享静态配方知识与有界持久化。 |
| [ADR-0008](adr/0008-snapshot-scoped-client-planning.md) | 授权快照作用域与同核客户端规划。 |
| [ADR-0009](adr/0009-recipe-route-selection-in-plan-details.md) | 在配方链详情中选择路线并按工作站预览配方。 |

## 测试与维护入口

- [测试说明](../../tests/README.md)：本地统一入口、回归分组、显式诊断，以及 CI 标准构建与专项的区别。
- [风险覆盖与断言边界](../../tests/coverage.md)：每类反例保护什么、哪些证据不能互相替代。
- [数据包夹具](../../tests/datapacks/README.md)：隔离世界中的配方/标签重载；不自动安装到玩家存档。

测试说明继续由 tests/ 维护。修改公开行为时同步中英指南；修改设计约束时更新对应现行小节；实际验证结果只登记其真实批次。


## 另注

文档中通常大面积的提及 "本机"，指的是 [@lewe189](https://github.com/lewe189) 开发和测试的电脑，同步说明的日志未上传等说辞也是日志保留在了 lewe189 的电脑上，没有上传至仓库。
