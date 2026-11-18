# 配方产量与材料标签重载夹具（仅独立测试世界）

不要放进真实游玩世界，不随正式 JAR 发布。两包分别测试，不同时启用。先使用默认配方制作木棍，让服务端学会关系；确保背包和附近箱子没有其他替代材料。

将选定文件夹（`recipe-yield-reload` 或 `ingredient-tag-reload`，包含 pack.mcmeta）复制到测试存档的 `datapacks/`，执行 `/reload`；用 `/datapack list enabled` 确认该包已启用，必要时执行 `/datapack enable "file/recipe-yield-reload" last` 或 `/datapack enable "file/ingredient-tag-reload" last`。

- `recipe-yield-reload`：同一 `minecraft:stick` 配方仍消耗两块木板，但一批产量从 4 改为 2。C/详情应显示并交付 2 根，不能沿用旧产量；重载前的详情需重新查询和确认。当前白名单按精确配方 ID 判断，同名替换产量不会自动移出名单；此夹具不单独证明部分准备授权或安全校验。
- `ingredient-tag-reload`：只将 `minecraft:planks` 标签替换为白桦木板。只有橡木板时木棍不可制作；换两块白桦木板后恢复，产量仍是 4。该标签也影响其他原版配方，不代表新模组玩法。

停服后移出本次测试包，再启动（或先 `/datapack disable "file/recipe-yield-reload"` / `/datapack disable "file/ingredient-tag-reload"`）；确认默认行为恢复。缓存文件属于此测试存档的 `craftable/cache/recipe-knowledge-v1.json`，不在客户端全局配置目录。

保留重载前后 `logs/latest.log` 中 `Craftable knowledge:` 的记录及实际数量截图。仅文件存在不能证明缓存正确；应同时核对当前库存、配方产量和状态刷新。
