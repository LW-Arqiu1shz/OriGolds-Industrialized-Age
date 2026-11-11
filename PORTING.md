> 2026-11-10：本工程现在位于 `源码\26.3NeoForge`，直接 `gradle build` 即可（在线解析依赖）。下文关于 `nfrepo/` 离线仓库、`PREFER_SETTINGS`、`tools/` 脚本的内容属于旧构建平台，已不再使用。本轮改动见同目录的 `VERSIONS.md` 开头。

# OriGolds: Industrialized Age —— Minecraft 26.3 / NeoForge 移植版

本目录是 **MC 26.3 + NeoForge** 的独立工程，与仓库根目录（`/workspace/mod`）的 **26.1.2** 工程**完全平行**：
各自有自己的 `settings.gradle` / `build.gradle` / `gradle.properties` / `src`，互不引用、互不覆盖。

| | 26.1.2 工程 | 26.3 工程（本目录） |
| --- | --- | --- |
| 源码 | `../src/main/java` | `./src/main/java` |
| 资源 | `../src/main/resources` | `./src/main/resources` |
| 版本串 | `1.0.0+26.1.2NeoForge` | `1.0.0+26.3NeoForge` |
| 产物 | `../dist/origolds_ia-1.0.0+26.1.2NeoForge.jar` | `./build/libs/origolds_ia-1.0.0+26.3NeoForge.jar`（发布副本在 `../dist/`） |
| ModDev 插件 | 2.0.146 | 2.0.148 |
| Java | 21 | 25 |

两份源码**同源不同枝**：改动若要同时生效，需分别在两边各改一次（移植期请先在 26.3 验证，再回灌 26.1.2，或反之）。

## 构建

```bash
# 在本目录执行；平台会自行选择 Gradle 8.12.1 + 对应 gradle slot
./gradlew build          # 产物：build/libs/origolds_ia-1.0.0+26.3NeoForge.jar
```

`mod_version` 里的 `-rN` 后缀由平台每轮构建自动追加，对外发布的版本号会在 `build.gradle` 的
`publishedVersion` 里剥掉，因此 jar 名与 `neoforge.mods.toml` 里的版本始终是 `1.0.0+26.3NeoForge`。

## 依赖为什么放在工程内（`nfrepo/`）

本容器**无法访问 `maven.neoforged.net`**（socket 直接不可达，不是 404；Gradle 遇到连接错误会让整个
依赖解析失败，而不会跳过这个仓库继续找下一个）。因此：

1. 所有 `net.neoforged.*` 构件按 Maven 目录布局**vendored 进 `nfrepo/`**（只保留 26.3 构建闭包，
   约 170 MB / 309 个文件，已纳入 git）。早期轮次曾整组拷贝，混进了别的项目的全部
   NeoForge 版本（63 个版本、~1 GB），第 26 轮已剪掉；仓库再膨胀时跑
   `python3 tools/prune_offline_repo.py --apply`（不加 `--apply` 为干跑），按与
   `sync_offline_repo.py` 相同的 5 根构件闭包修剪。
2. `settings.gradle` 用 `dependencyResolutionManagement { repositoriesMode = PREFER_SETTINGS }`
   **屏蔽 ModDev 插件注入的三个远程仓库**（`NeoForged Releases`、`Mojang Minecraft Libraries`、
   `Mojang Meta`）。构建日志里仍会打印“prefer settings repositories … was added by plugin”，这是
   提示而非错误——被提示的仓库确实被忽略了。
3. `build.gradle` 里**不写 `repositories {}`**，仓库一律在 `settings.gradle` 声明。

可用的远程仓库只有 `repo1.maven.org`（Central）、`libraries.minecraft.net`、`launchermeta.mojang.com`、
`plugins.gradle.org`、`api.github.com`。LWJGL 等通用库走 Central，Mojang 运行库走 `libraries.minecraft.net`。

### `minecraft-dependencies:26.3` 是合成的

NeoForm 会依赖一个与游戏版本同名、但**从未发布过 POM/module** 的构件
`net.neoforged:minecraft-dependencies:26.3`（26.1.2 有官方的，26.3 没有）。
`tools/gen_minecraft_dependencies.py` 的做法是：拿**官方 26.1.2 的 `.module` 当骨架**，
用 Mojang 的 26.3 版本清单（`version_manifest` → 具体版本 JSON）替换全部库条目，
生成 26.3 的 `.module`，放到 `nfrepo/net/neoforged/minecraft-dependencies/26.3/`。

```bash
# 重新生成（--check 会先自证能逐条复现官方 26.1.2 文件，再产出 26.3 版）
T=$(find ../.gradle-user -name 'minecraft-dependencies-26.1.2.module' | head -1)
python3 tools/gen_minecraft_dependencies.py \
    --manifest /tmp/mc-26.3.json --template "$T" --version 26.3 \
    --emit nfrepo/net/neoforged/minecraft-dependencies/26.3/minecraft-dependencies-26.3.module
```

两个坑：

- Maven 布局的仓库里，想让 Gradle 认 `.module`，旁边必须有一个带
  `<!-- do_not_remove: published-with-gradle-metadata -->` 标记的 `.pom`，否则报
  “Could not find … Searched in: …/minecraft-dependencies-26.3.pom”。
- 26.3 移除了 `lwjgl-glfw`/`tinyfd`，新增了 `lwjgl-sdl`、`shaderc`、`spvc`、`vma`、`vulkan` 3.4.3，
  生成脚本按清单自动处理，不要手抄。

### 同步 vendored 构件

```bash
python3 tools/sync_offline_repo.py   # 沿依赖闭包把构件从平台 gradle 缓存复制成 Maven 布局
```

`locate()` 会**跨所有 gradle slot 合并**同一构件的文件：只看第一个命中的 slot 会只拿到
`-fatjar.jar` 而没有 pom，从而在解析期报 “Could not find”。平台缓存内容是共享且可变的，
**不要把构建正确性寄托在 slot 内容上**——缺什么就 vendor 进 `nfrepo`。

## 移植到 26.3 时踩到的 API 变更

本目录源码是 26.1.2 的拷贝加上下列适配（每处都在代码里留了一行中文说明）：

- **物品能力**：`net.neoforged.neoforge.items`（`IItemHandler`、`ItemStackHandler`、`InvWrapper`、
  `ItemHandlerHelper`）整包删除，改为 `net.neoforged.neoforge.transfer.*`。
  `Capabilities.Item.BLOCK` 现在是 `BlockCapability<ResourceHandler<ItemResource>, Direction>`；
  模拟（simulate）不再是布尔参数，而是开一个 `Transaction` 不提交。
  本模组用 `VanillaContainerWrapper`/`ItemStackResourceHandler` 包装容器，自定义策略处
  （锯片台、抽屉）改成继承 `SnapshotJournal` 自己实现 `ResourceHandler`。
- **输入**：GLFW 被移除，键盘/鼠标走 SDL（`com.mojang.blaze3d.platform.InputConstants`，
  `isKeyDown(int)` 单参 + SDL scancode）。修饰键优先用 `Minecraft.hasShiftDown()` 等。
- **当前界面**：`Minecraft.screen` / `setScreen` / `getOverlay()` 移到 `mc.gui` 上
  （`screen()` / `setScreen()` / `overlay()`）。
- **输入框过滤**：`EditBox.setFilter(Predicate)` 被删（只剩显示层的 `addFormatter`）。
  本模组用 `OGEFilteredEditBox` 在 `charTyped`/`insertText` 处“写入后校验、不合法整体回退”补齐语义，
  并保证被拒绝的输入与回退写回都不会误触发 `setResponder`。
- **实体/世界**：`swing(hand, boolean)` → `swing(hand, SwingAnimation, boolean)`；
  `Player.drop(stack, boolean)` → `drop(stack, boolean, Prediction)`；
  `Entity.invulnerableTime` 私有化（改 `get/setInvulnerableTime`）、`setInvulnerable` →
  `setPermanentlyInvulnerable`、`hurtMarked` → `syncVelocity`；
  `BlockPos.getCenter()` → `Vec3.atCenterOf(pos)`；`playerDestroy` 收 `ServerLevel`/`ServerPlayer`；
  `isValidBonemealTarget` 末尾加 `BonemealSource`。
- **方块**：`codec()` / `simpleCodec(...)` 机制整体移除（原先 10 个方块的覆写已删除）；
  `PushReaction.DESTROY` → `POPPED`。
- **流体**：`BlockState.blocksMotion()` 没了，原版把判据做成方块标签
  `BlockTags.WASHED_AWAY_BY_FLUIDS`（见 `OGEFluidGuard`）。
- **燃料**：`level.fuelValues()` 没了，燃烧时间改从物品的 `DataComponents.COOKING_FUEL` 组件解析
  （`ResolvableInt.getFromItem(...)`，与原版熔炉一致）。
- **渲染**：`GameRenderer.getGameRenderState()` → `gameRenderState()`；
  `Sheets.translucentBlockSheet()` → `translucentBlockItemSheet()`；
  `submitMovingBlock` 多了 `outlineColor` 第三参（原版传 0）；
  `PoseStack.mulPose(Quaternionf)` 不再存在，改传矩阵（`Matrix4f`）。

## 父模组 `origolds_ar`

线路显示要对接父模组 `origolds_ar` 预留的 WireVisuals API。目前**父模组还没有 26.3 版**，
因此 `build.gradle` 里以 `compileOnly files('libs/origolds_ar-1.2.0_26.1.2NeoForge.jar')` 仅做链接，
不打进产物；运行期由 `OGEWireVisualsBridge` 探测，父模组缺席或不兼容时回退本模组自绘。
父模组出 26.3 版后，把 jar 换进 `libs/` 即可，无需改代码。
