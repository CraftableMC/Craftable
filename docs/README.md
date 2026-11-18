# Craftable documentation / 文档导航

Craftable reduces repetitive crafting interactions through Minecraft's own
Recipe Book. It uses resources in the current accessible working environment,
explains recipe chains, and leaves permissions and final execution to the server.
It does not add a storage network or a new progression system.

Craftable 通过原版配方书减少重复合成交互：使用当前可访问工作环境中的资源，解释配方链，
并由服务端决定权限及最终执行。不增加存储网络或新的成长体系。

## Choose a guide / 选择指南

| Goal / 阅读目的 | English | 简体中文 |
| --- | --- | --- |
| Learn about the mod / 了解模组 | [Introduction](../README.md) | [模组介绍](../README.zh-CN.md) |
| Install and play / 安装与游玩 | [User guide](user-guide.en.md) | [用户指南](user-guide.zh-CN.md) |
| Build and contribute / 构建与贡献 | [Developer guide](developer-guide.en.md) | [开发指南](developer-guide.zh-CN.md) |
## Design and verification / 设计与验证

- [Development materials / 开发资料索引](development/README.md)：现行设计依据、计划与验收、深度维护参考、历史记录和 ADR，分类阅读，不统称过时档案。
- [Testing / 测试说明](../tests/README.md)：本地统一入口、回归分组和 CI/人工边界；命令集中维护在这里。
- [Coverage / 风险覆盖](../tests/coverage.md)：保留测试能抓住哪些错误，以及断言不能证明什么。

Numbered development records remain primarily in Chinese and retain their file
names for traceability. Only the long-lived public user and developer guides are
maintained as English/Chinese pairs. Screenshots and branding live in `assets/`.

编号开发资料主要保留中文及原文件名，便于追溯；长期公开的用户和开发指南按中英成对维护。
截图及标识等公共资源放在 `assets/`。根 README 负责模组介绍，这里负责阅读导航，
`development/README.md` 负责详细开发资料索引。

## License / 许可

[CraftableMC Community License 1.0](../LICENSE) · [中文译文](../LICENSE.zh-CN)

The full license, not this navigation page, defines permissions and restrictions.
授权及限制以完整许可为准，本导航页不替代许可条款。
