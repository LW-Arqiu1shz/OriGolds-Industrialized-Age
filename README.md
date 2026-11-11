# 金源工艺：工业时代 · OriGolds: Industrialized Age

> 本分支 / This branch: **1.21.1NeoForge**

[中文](#中文) · [English](#english)

> Author / 作者：**Arqiu-owo** · Code: `LGPL-3.0-only` · Assets: `CC-BY-NC-SA-4.0` — see [LICENSE.md](LICENSE.md)

---

## 中文

用金源能量把整个世界的活儿都交给机器。加了一整套发电、储电、无线输电、无线物流和加工机器，外加两件用来挂机和砍人的小玩意。

本模组是《[金源工艺](https://github.com/LW-Arqiu1shz/OriGolds)》（OriGolds）的附属模组，**必须安装金源工艺**（1.1.2 及以上）。
建议同时安装《[金源工艺：古金极锻](https://github.com/LW-Arqiu1shz/OriGolds-Ancient-Reforge)》（OriGolds: Ancient Reforge）：
26.x 版本的线路显示会调用它的视觉接口，没装也能玩，只是线路退回本模组自带的绘制方式。

客户端和服务端都要安装。

### 功能

**金源能量（OGE）**

- 四种发电机：风驱每秒 1u（放哪都行）、水驱每秒 2u（放进水里）、火驱每秒 10u（烧燃料）、魔驱每秒 50u（烧魔金之核等）。
- 金源蓄能电容：最多存 60000u。
- 金源能量传输线：Ctrl+左键选起点、Ctrl+右键选终点，连起来的机器自动并成同一个能量网络，互相输电。
  手持时 Ctrl+中键切换显示模式，悬停选中线路后按 Delete 删除，Ctrl+Z / Ctrl+Y 撤销与重做。

**资源与加工**

- 沙砾 / 黏土 / 黑曜石发生器：每秒消耗 5u / 10u / 50u 金源能量，产出一块。
- 金源伐木模块：连锁采集树木和相连的树叶；金源采掘模块：破坏相邻的可采集方块。
- 金源粉碎机：5u 把粗矿碎成 2 个精矿，或让矿石产出翻倍；也能粉碎黑曜石。
- 金源筛洗机：5u 筛洗沙砾 / 黏土得到闪亮碎块、碎片、碎粒，再洗一次就能看清里面是什么。
- 金源超级熔炉：用金源能量烧炼，可切换速度档位（越快越费电）。
- 金源自动合成机：锁住配方后只接受配方里的物品，够料就自动合成；顶部浮着产物，四面是 3×3 的锁定格。
- 金源自动附魔机：消耗金源能量做铁砧的活儿，还能把附魔融合到**原上限的 2 倍**，
  也能给物品祛魔、把附魔书上的附魔转走。有四档速度。
- 金源炼金站：积攒 2048u 炼金凝造价量，用金源能量换 1 个金锭。

**物流与存储**

- 金源超级漏斗：漏取间隔和单次数量都能设，输入 / 输出端口可沿轴向旋转，也能绑定成只收某一种物品。
- 金源虚空抽屉：一种物品存 729 组，逐格解锁。
- 金源引力匣：范围可调、能绑定物品的掉落物收集器。
- 金源销毁站：某一格的物品在一段时间内数量不减，就判定为垃圾并销毁（Alt+左右键调等待时间）。
- 无线物流管道：Alt+左键选起点、Alt+右键选终点，单向传输；悬停可选中，Delete 删除、Backspace 反转方向。

**其他装置**

- 金源放置器：绑定方块后可在以它为中心的 9×9×9 范围里摆蓝图，之后定时自动放置。
- 金源鼓风机：吹出 9 格长、9m/s 的风场，长度和风速都能调。
- 金源坷垃机：范围内催熟、让荒芜的泥土重新长草，范围可调。
- 金源红石脉冲钟：按设定的间隔发出红石脉冲。
- 虹彩印记：贴在发生器上让它超频工作（速度、产量提升，额外耗能），用魔金之核补充次数，档位可切。

**挂机与战斗**

- 挂机志愿者：假人。玩家模式下强制加载周围区块、允许刷怪；仇恨目标模式下持续吸引周围生物的仇恨。
  Shift+左键切换模式，Shift+右键收回。
- 金源圆锯刃槽 + 圆锯刃片：装上刃片和任意近战武器，耗金源能量自动攻击范围内的目标；
  击杀掉的经验可以存起来取走，附魔了经验修补的攻击器会优先拿去修补。

> 大部分机器靠 Ctrl / Alt 加左右键、中键的组合来调参数（范围、档位、间隔、绑定），物品提示里写着具体组合。

### 支持的版本

| 分支 | Minecraft | 加载器 |
|---|---|---|
| [`1.21.1NeoForge`](../../tree/1.21.1NeoForge) | 1.21.1 | NeoForge 21.1.244+ |
| [`26.1.2NeoForge`](../../tree/26.1.2NeoForge) | 26.1.2 | NeoForge 26.1.2.103+ |
| [`26.3NeoForge`](../../tree/26.3NeoForge) | 26.3 | NeoForge 26.3.0.51-beta+ |

每个版本是仓库里的一个**分支**（`1.21.1NeoForge` 是默认分支，也是功能基准版本），分支根目录就是 Gradle 工程。
更新记录见 [CHANGELOG.md](CHANGELOG.md)。

### 自己编译

需要 **Gradle 9.7+**（仓库里没有附带 Gradle Wrapper），以及对应的 JDK（1.21.1 用 JDK 21，26.1.2 / 26.3 用 JDK 25）。
切换到想要的版本分支，在根目录运行：

```
gradle build
```

jar 会生成在 `build/libs/` 里。

> 26.1.2 / 26.3 分支在编译期引用父模组《古金极锻》的线路显示接口（`compileOnly`，不会打进产物），
> 需要先把 1.2.1 版本的 `origolds_ar` jar 放进 `libs/`，否则编译不过。那个 jar 不在本仓库里，
> 请到[古金极锻 1.2.1 Release](https://github.com/LW-Arqiu1shz/OriGolds-Ancient-Reforge/releases/tag/1.2.1) 下载对应版本的 jar（`origolds_ar-1.2.1+26.1.2NeoForge.jar`
> 或 `origolds_ar-1.2.1+26.3NeoForge.jar`），文件名不要改，直接放进 `libs/`。

---

## English

Hand the world's chores to machines, powered by OriGolds energy. This mod adds generators, energy storage,
wireless power and wireless item transport, a full set of processing machines, plus two toys for AFKing and cutting things.

This is an add-on for [OriGolds](https://github.com/LW-Arqiu1shz/OriGolds); **OriGolds (1.1.2 or newer) is required**.
[OriGolds: Ancient Reforge](https://github.com/LW-Arqiu1shz/OriGolds-Ancient-Reforge) is recommended: the 26.x branches
use its wire-visual API, and without it the wires simply fall back to this mod's own renderer.

Install it on **both the client and the server**.

### Features

**OriGolds Energy (OGE)**

- Four generators: wind (1u/s, works anywhere), water (2u/s, must be in water), fire (10u/s, burns fuel),
  magic (50u/s, burns Magigold Cores and similar).
- Origold Energia Capacitor: stores up to 60000u.
- Origold Energie Wire: Ctrl+left click for the start, Ctrl+right click for the end; connected machines join one
  energy network and share power. Ctrl+middle click toggles the display mode, hover a wire and press Delete to remove it,
  Ctrl+Z / Ctrl+Y to undo and redo.

**Resources and processing**

- Gravel / Clay / Obsidian generators: 5u / 10u / 50u per block.
- Origold Logger fells a whole tree with its leaves; Origold Driller breaks adjacent harvestable blocks.
- Origold Pulverizer: 5u turns raw ore into 2 refined ore, or doubles an ore's drops; it also grinds obsidian.
- Origold Sifter: 5u washes gravel / clay into shiny chunks, shards and crumbs; wash them again to see what is inside.
- Origold Hyperfurnace: smelts with energy, with speed tiers (faster burns more).
- Mech Crafter: lock a recipe and it only accepts those items, then crafts automatically; the result floats on top and
  the 3×3 lock grid sits on the four sides.
- Mech Enchanter: an anvil that runs on energy, and can fuse enchantments up to **twice their normal maximum level**;
  it also disenchants items and moves enchantments off books. Four speed tiers.
- Chrysopoeia Station: collects chrysopoeia value (2048u per ingot) and spends energy to produce gold ingots.

**Logistics and storage**

- Hyperhopper: configurable interval and batch size, rotatable input / output ports, bindable to one item type.
- Void Drawer: 729 stacks of a single item, unlocked slot by slot.
- Gravity Cassette: an item collector with a configurable range and optional item binding.
- Voiding Station: if the item count in a slot has not dropped for a while, it is treated as junk and destroyed
  (Alt+left/right click changes the wait time).
- Wireless Logistics Pipe: Alt+left click for the start, Alt+right click for the end; one-way transport, hover to select,
  Delete to remove, Backspace to reverse.

**Other devices**

- Origold Placer: bind a block, lay out a blueprint within a 9×9×9 area around it, and it places blocks on a timer.
- Origold Blower: a 9-block-long wind field at 9 m/s, with adjustable length and strength.
- Origold Kela Machine: bonemeals a whole area and restores grass on dirt, with an adjustable range.
- Origold Redstone Pulse Clock: emits redstone pulses at a configurable interval.
- Rainbow Mark: slap it on a generator to overclock it (more speed and output, extra energy cost); refill it with
  Magigold Cores and switch its tier.

**AFK and combat**

- Volunteer Watcher: a dummy. In player mode it force-loads the surrounding chunks and allows mob spawning; in
  mob-target mode it keeps drawing mob aggro. Shift+left click switches modes, Shift+right click picks it up.
- Origold Sawblade Mount + Circular Sawblade: install a blade and any melee weapon, and it spends energy to attack
  targets in range; the XP from kills is stored for you to collect, and Mending weapons are repaired with it first.

> Most machines are configured with Ctrl / Alt plus left, right or middle click (range, tier, interval, binding);
> the item tooltips list the exact combinations.

### Supported versions

| Branch | Minecraft | Loader |
|---|---|---|
| [`1.21.1NeoForge`](../../tree/1.21.1NeoForge) | 1.21.1 | NeoForge 21.1.244+ |
| [`26.1.2NeoForge`](../../tree/26.1.2NeoForge) | 26.1.2 | NeoForge 26.1.2.103+ |
| [`26.3NeoForge`](../../tree/26.3NeoForge) | 26.3 | NeoForge 26.3.0.51-beta+ |

Each version lives on its own **branch** (`1.21.1NeoForge` is the default branch and the reference version for features);
the branch root is the Gradle project. See [CHANGELOG.md](CHANGELOG.md) for the changelog.

### Building

Requires **Gradle 9.7+** (no Gradle Wrapper is included) and **JDK 21** (1.21.1) or **JDK 25** (26.1.2 / 26.3).
Check out the branch for the version you want and run, in the repository root:

```
gradle build
```

The jar is written to `build/libs/`.

> The 26.1.2 / 26.3 branches reference the wire-visual API of OriGolds: Ancient Reforge at compile time
> (`compileOnly`, it is not bundled), so you need version 1.2.1 of that mod's jar in `libs/` or the build fails.
> That jar is not included in this repository: download the matching jar (`origolds_ar-1.2.1+26.1.2NeoForge.jar`
> or `origolds_ar-1.2.1+26.3NeoForge.jar`) from the [Ancient Reforge 1.2.1 release](https://github.com/LW-Arqiu1shz/OriGolds-Ancient-Reforge/releases/tag/1.2.1)
> and put it in `libs/` without renaming it.

---

## 贡献者 / Contributors

- **Arqiu-owo** — 设计、美术、文案、调试 / Design, art, writing and testing
- **Claude**（Anthropic）— 程序、项目管理 / Programming and project management
- **DeepSeek** — 程序、项目管理 / Programming and project management

## License / 许可证

- Code / 代码：[GNU LGPL v3.0](COPYING.LESSER)（with [GPL v3.0](COPYING)）
- Assets (images) / 素材（图片）：[CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/)

Details / 详情：[LICENSE.md](LICENSE.md)
