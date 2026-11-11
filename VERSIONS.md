# 2026-11-10 整理说明（以本节为准）

源码已从两个 zip 拆成三套并排的独立 Gradle 工程，放在 `源码\` 下：
`1.21.1NeoForge`（Java 21）、`26.1.2NeoForge`、`26.3NeoForge`（Java 25）。每套各自 `gradle build`，
jar 在各自的 `build\libs\`。26.x 编译期用的父模组 API jar 放在各自的 `libs\`
（古金极锻 `origolds_ar-1.2.1+<版本>NeoForge.jar`）。下文里 `/workspace/mod`、`mc26.3/`、`nfrepo/`、
`dist/` 是旧平台的布局，已不再使用；26.3 也不再需要离线仓库。

1.0.2 26.3 r32（2026-11-10）：26.3 模组列表双语适配复核——名称走官方 `ModDisplayInfo` 扩展点（`OriGoldsIndustrializedAgeClient`，键 `neoforge.screen.mods.info.displayname.origolds_ia`），简介用 `neoforge.screen.mods.info.description.origolds_ia`，搜索中英文名都能命中（`ModListSearchMixin`）；该 Mixin 改为 `require = 0`，NeoForge 改动 lambda 时只少双名搜索、不会启动崩溃。仅 26.3。

1.0.2（1.21.1 r170 / 26.x r31）：金源机械合成台顶部浮空产物与四面配方格同时显示（三版一致）；合成改为按实际材料匹配与组装（特殊配方可合、配方剩余物从顶面送出，与原版合成器一致）；依赖 origolds ≥ 1.1.2；26.x 锯片伤害取消难度缩放；26.x 志愿者绑定界面的悬停提示、垃圾桶高亮、面板底色/宽度、拖拽起手与 1.21.1 对齐。

1.0.1 r169 / 26.x r30 改动：
- 无线电网连通分量改为缓存（连线增删才重建），不再每台机器每次耗电都重新建图；耗电时储能能力只查一次。
- 金源坷垃机停机时先查骨粉，开轮失败后隔 10 刻再试，不再每刻扫描整片范围。
- 发电机/电池只在显示数值变化时才发同步包（另每秒兜底一次）。
- 下文“移植审查遗留项”第 1 条（旧存档无线数据迁移）已修复：`OGELegacyDataMigration` 在开服时导入
  1.21.1 的 `origolds_ia_oge_wireless.dat` / `origolds_ia_oge_logistics.dat`，导入后改名 `*.migrated`；
  锯片台也认 1.21.1 的 `BoundVolunteer` UUID。
- 第 4 条（经验球重定向）已修复：26.x 的 `XpOrbTargetingEvent` 在 `net.neoforged.neoforge.entity` 包，照 1.21.1 恢复。

# 版本目录布局（两套源码都在维护）

同一个模组的两个 Minecraft 目标版本，**并排各放一套完整工程**，不共享 `src`，不用构建脚本里的
版本开关切换——这样两边都能独立编译、独立发布，互不影响。

```
/workspace/mod
├── src/                     ← 26.1.2 源码
├── build.gradle             ← 26.1.2 构建脚本（ModDev 2.0.146 / Java 25）
├── gradle.properties        ← mod_version = 1.0.1+26.1.2NeoForge
├── libs/                    ← compileOnly 的父模组 origolds_ar jar（两棵树共用同一个文件路径约定）
├── dist/                    ← 两个版本的发布 jar 都放这里
│   ├── origolds_ia-1.0.1+26.1.2NeoForge-rN.jar
│   └── origolds_ia-1.0.1+26.3NeoForge-rN.jar
└── mc26.3/                  ← 26.3 移植版（自成一套工程，详见 mc26.3/README.md）
    ├── src/                 ← 26.3 源码
    ├── build.gradle         ← ModDev 2.0.148 / Java 25
    ├── gradle.properties    ← mod_version = 1.0.1+26.3NeoForge
    ├── settings.gradle      ← PREFER_SETTINGS：屏蔽插件注入的不可达远程仓库
    ├── nfrepo/              ← vendored 离线 Maven 仓库（构建必需，容器连不上 maven.neoforged.net；
    │                         仅保留 26.3 构建闭包：170 MB / 309 个文件，第 26 轮剪枝）
    └── tools/               ← 离线仓库同步/剪枝与合成元数据的生成脚本（prune_offline_repo.py 按闭包删除无关构件）
```

## 约定

- **mod id** 两边都是 `origolds_ia`，显示名都是 `OriGolds: Industrialized Age`；
  只有版本串按 `<MC版本><Loader>` 区分（`1.0.1+26.1.2NeoForge` / `1.0.1+26.3NeoForge`）。
- 平台每轮构建会往 `mod_version` 追加 `-rN`。自 1.0.1 起按用户要求：
  **模组内版本号（mods.toml）不带 `-rN`**（由各 `build.gradle` 的 `publishedVersion` 剥掉），
  **jar 文件名保留 `-rN`**（`version = mod_version`，如 `origolds_ia-1.0.1+26.1.2NeoForge-r29.jar`）。
- **两边同源不同枝**：一个行为修复要落到两个版本，必须在两棵源码树里各改一遍。
  移植期建议先在一边验证，再回灌另一边。
- `.blockforge-deliverable.json` 每次交付指向 `dist/` 里应当被下载的那个 jar，
  另一个版本的 jar 始终留在 `dist/` 里不删。
- 资源目录两棵树逐字节一致（498 个文件），Java 侧只有约 66 个文件因 API 差异而不同。
  改资源时记得两边都要落。

## 两版本关键差异速查

| 项 | 26.1.2 | 26.3 |
| --- | --- | --- |
| NeoForge | 26.1.2.103 | 26.3.0.51-beta |
| ModDev Gradle 插件 | 2.0.146 | 2.0.148 |
| Java | 25 | 25 |
| 数据包格式 | resource `84.0` / data `101.1` | resource `97.1` / data `121.0` |
| `pack.mcmeta` 上限 | `max_format [101, 1]` | `max_format [121, 0]` |
| 物品能力 | `neoforge.items.IItemHandler` | `neoforge.transfer.*`（`ResourceHandler<ItemResource>`） |
| 输入后端 | GLFW（`isKeyDown(Window, int)`；鼠标键码 左=0/右=1/中=2） | SDL（`InputConstants.isKeyDown(int)`，扫描码；鼠标键码 **左=1/中=2/右=3**——屏幕里必须用 `InputConstants.MOUSE_BUTTON_*`/`KEY_*` 常量比较，沿用 GLFW 字面量会错一位） |
| 当前界面 | `Minecraft.screen` | `mc.gui.screen()` |
| 帧渲染线程 | 与客户端刻同线程 | 仍是同一游戏线程（`run()→renderFrame→GameRenderer.extract/render`），但拆成主线程抽取渲染态、后续消费的阶段（`LevelExtractor`） |
| 世界容器线程检查 | 无 | `net.minecraft.util.ThreadingDetector` 存在，但只挂在 `PalettedContainer.acquire()/release()`（写侧）；`get()` 无锁读 |
| 活塞反应 | `PushReaction.DESTROY` | `PushReaction.POPPED` |
| 燃料值 | `level.fuelValues()` | `DataComponents.COOKING_FUEL` 组件 |

完整移植改动清单见 [`mc26.3/README.md`](mc26.3/README.md)。

## 渲染路径与线程模型（2026-11-03 反汇编复核，勿再凭旧理论动手）

第 25 轮曾按"26.3 帧渲染独立线程 + `ThreadingDetector` 卡渲染线程"的理论，把
`OGEWirelessConnectorClient`/`OGEPlacerGhosts`/`OGEPlacerRenderer`/`OGEVolunteerBindClient`/
`OGEWireVisualsChannels` 的世界访问改成客户端刻快照。第 26 轮对 26.3.0.51-beta 构件反汇编复核，
该理论的机制**不成立**：

- `Minecraft.run() → renderFrame → GameRenderer.extract → GameRenderer.render →
  renderLevel → LevelRenderer.render → submitFeatures（触发 `SubmitCustomGeometryEvent`）`
  ——全链路都在游戏主线程，26.3 没有独立帧渲染线程；
- `ThreadingDetector.checkAndLock()` 全 jar 只被 `PalettedContainer.acquire()/release()` 调用
  （写侧配对锁），`get()` 是 volatile 无锁读——读世界不可能阻塞在信号量或抛线程检查异常。

处置：

- **26.1.2**：第 25 轮为卡死问题做的改动已全部回退（6 个文件回退到第 25 轮开工前状态）。
  用户确认 26.1.2 没有卡死问题，那批改动对它没有任何收益。
- **26.3**：快照化改动**保留**。它虽然治不了卡死，但行为等价、附带消除了进出世界过渡期的
  `mc.level`/`mc.player` 空窗竞态和 `quickBindBound` 边改边读的 CME 风险，是无害改进。
- **第 29 轮**：两版的全部世界方块高亮（志愿者快速绑定/预览、放置器投影外框线）接入
  `OGEWireVisualsChannels` 的父模组 WireVisuals 通道（共 4 条通道），穿墙可见、Iris 兼容，
  父模组缺席时仍回退旧自绘。为此把 `OGEVolunteerBindClient` 的刻级快照机制按 26.3 同款
  **重新引入 26.1.2**——这是通道数据访问与两树结构对齐的需要（每刻一次射线替代每帧一次），
  不是卡死修复；两版观感与行为一致。

**26.3 进世界崩溃/卡加载的真实根因仍未定位**（该版本自移植后从未成功进入世界）。
已静态排除：渲染回调世界访问、自定义配方/loot/同步包编解码、payload 处理器线程。
下一步必须拿用户实例的最新崩溃报告（`crash-reports/crash-*.txt` 或 `logs/latest.log`）
定位 `Caused by`，不要再按猜测改动代码。

## 移植审查遗留项（尚未处理，不是本轮引入）

1. **无线连线与物流管道的旧世界存档迁移缺失（高）**。1.21.1 用平铺文件
   `origolds_ia_oge_wireless.dat` / `origolds_ia_oge_logistics.dat`，根 NBT 是 `links` / `pipes`
   列表；26.x 改成 `SavedDataType` 标识 `origolds_ia:oge_wireless` / `origolds_ia:oge_logistics`
   并以 Codec 列表为数据结构。路径与根格式都变了，自动迁移没写。**测旧世界一律先用副本。**
2. **锯片伤害缩放与 1.21.1 不同**。`data/origolds_ia/damage_type/sawblade.json` 的 `scaling`
   从 `never` 变成 `when_caused_by_living_non_player`，伤害会随难度与来源类型变化。
   保留 fork 设定，需在普通/困难难度确认设计意图。
3. **父模组依赖范围放宽**。`neoforge.mods.toml` 里 `origolds` 的 `versionRange` 是 `*`，
   任意版本都会被加载器接受，但不保证 API 兼容。
4. **经验球重定向未完全恢复**。`PlayerXpEvent.PickupXp` 里已能拦住隐藏代理捡经验，
   但 26.1.2/26.3 都找不到"把经验球追踪目标从代理改回真人玩家"的等价钩子，球仍可能被代理吸到跟前。
5. **志愿者绑定 UI 悬停提示有简化**（旧版整行 500ms 提示 + 垃圾桶高亮，改成文本框 Tooltip）。
   核心绑定/改名/删除/排序功能未缺失。
6. **两张字节相同但 ID 不同的贴图**：`gravitycassette_front.png` 与 `gravitycassette_front_near.png`
   是两个资源 ID，不能被当作重复文件删除。

## 性能优化的既有结论（保留，勿回退）

- `OGEWireVisualsChannels`：全部线状样式（现 4 条通道）的 `WireVisualStyle` 一律 `glow(0.0f)`。父模组的
  `wire()` 在 `glowScale > 0` 时会按同样的细分段数**再画一份更宽的加色丝带**，开光影时这部分
  是主要开销；已去掉的只有这层额外辉光，线色/渐变/粗细/流光/端点框/悬停/呼吸框全保留。
  端点填充也不重复画：`WireVisualLinks.render()` 已提供，通道里不再补第二层六面填充。
- 链路快照 → `WireVisualLink` 的换算走 `LinkCache`，按游戏刻 + 源列表身份重建，不再每帧新建。
- 准星指向的引力匣范围框、投影线框等一律走刻级快照（见上文渲染线程纪律），顺带把
  每帧 N 次 `getBlockState` 降到每刻 1 次。
- `OGEPlacerRenderer`：视锥剔除 + 所有可见投影线框合并成一个提交节点（原来每格一个节点，
  投影多时会产生大量小型 translucent 提交，光影下尤其贵）。
- `OGERainbowOverlayRenderer`、`OGESawbladeRenderer`：生物群系查询用 `getBiome(pos)`
  （走客户端缓存），不用 `getUncachedNoiseBiome(...)`。
- **仍存在的透明材质成本**：金源能源电容 0–14 档模型各 36–41 面，其中 4 个侧面用
  `force_translucent` 外壳贴图（16×16、alpha=128）——这是设计上的磨砂玻璃效果；彩虹覆层同样是
  完整六面 translucent 模型。若掉帧集中在看电容器或彩虹标记机器时，这些面是 Iris 下的
  填充/混合成本来源。未实测前不要把 FPS 问题全归给它，也不要为了帧数擅自改成不透明。

## 配方 JSON 格式纪律（26.1.2 与 26.3 相同，勿回退）

- **自定义 ingredient 的分发键是 `neoforge:ingredient_type`，不是 `type`**。两个版本的
  NeoForge（26.1.2.103 / 26.3.0.51-beta）源码里 `IngredientCodecs.codec` 都写成
  `dispatch("neoforge:ingredient_type", ...)`（两侧 sources jar 实测比对过）。
  写成 `"type": "neoforge:difference"` 会让整条配方解析失败被丢弃。
  条件（`neoforge:conditions` 里的 `"type": "neoforge:mod_loaded"`）仍走 DFU 默认 `"type"`
  键分发，不受影响，勿顺手"统一"。
- **ingredient 字符串标签必须指向运行时真实存在的标签**：原版 26.x 没有
  `#minecraft:cobblestone` item 标签，`HolderSetCodec` 查不到直接报
  `Missing tag: 'minecraft:cobblestone' in 'minecraft:item'` 并丢配方；要用 NeoForge
  自带的约定标签（`#c:cobblestones`，其下还有 `/normal`、`/mossy`、`/infested`、
  `/deepslate` 子标签）。涉及文件：`data/minecraft/recipe/raw_{iron,copper,gold}_block.json`
  （差集排除带 `origolds_ia:oge_concentrated` 组件的精矿）与
  `data/origolds_ia/recipe/water_origold_energia_generator.json`。
- 内层 `"neoforge:components"` 的 `items` 字段接受单个物品 ID 字符串或列表；
  `components` 的值按对应 `DataComponentType` 的 persistent Codec 序列化
  （`oge_concentrated` 是 `holderByNameCodec`，所以 `"minecraft:raw_iron"` 合法）。
- 第 17 轮发现这两个配方格式在**两个 fork 上都曾写错**（`"type"` 键 + 不存在的
  `#minecraft:cobblestone`），两边已同步修复；不是 26.3 独有的行为差异。
