package cn.origolds.ia;

import cn.blockforge.generated.origoldsar.api.visual.WireVisualCanvas;
import cn.blockforge.generated.origoldsar.api.visual.WireVisualColor;
import cn.blockforge.generated.origoldsar.api.visual.WireVisualContext;
import cn.blockforge.generated.origoldsar.api.visual.WireVisualLayer;
import cn.blockforge.generated.origoldsar.api.visual.WireVisualLink;
import cn.blockforge.generated.origoldsar.api.visual.WireVisualLinks;
import cn.blockforge.generated.origoldsar.api.visual.WireVisuals;
import cn.blockforge.generated.origoldsar.api.visual.WireVisualStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 本模组全部世界高亮接入父模组（origolds_ar 1.2.0+）预留的
 * {@code WireVisuals} 线路显示通道。
 *
 * <p>注册四条通道，与原自绘路径一一对应、互斥生效（由 {@link OGEWireVisualsBridge}
 * 决定本类是否被加载）：</p>
 * <ul>
 *   <li><b>{@code origolds_ia:oge_wireless_energy}</b>——金源能量显示模式
 *       （Ctrl+中键）：金色无线电线、端点框与填充、悬停高亮、选点呼吸框、准星白框；</li>
 *   <li><b>{@code origolds_ia:oge_logistics_pipes}</b>——无线物流显示模式
 *       （Alt+中键）：青→橙渐变的单向管道线（起点端框青、终点端框橙，由渐变线色
 *       自动采样）、悬停高亮、选点呼吸框、引力匣吸取范围外框、准星白框；</li>
 *   <li><b>{@code origolds_ia:oge_volunteer_bind}</b>——挂机志愿者快速绑定/预览：
 *       可绑锯片台白框、准星命中加粗框+亮填充、已绑淡填充、预览连线与目标格；</li>
 *   <li><b>{@code origolds_ia:oge_placer_frames}</b>——金源放置器投影的每格外框线
 *       （已保存 #c99951、编辑中/预览白色；半透明方块虚影仍走原版提交管线）。</li>
 * </ul>
 *
 * <p>走父模组管线的收益：{@code OVERLAY} 层深度策略让框线<b>穿墙可见</b>，
 * 管线在 Iris 光影下按本模组自己的渲染通道处理，不再有旧自绘（{@code linesTranslucent}
 * /{@code debugQuads}）被深度测试挡住、被光影阴影通道二次着色的问题。</p>
 *
 * <p>悬停结果写回 {@link OGEWirelessConnectorClient} 的 hoveredLink/hoveredPipe，
 * Delete 删除与 Backspace 反转的按键逻辑不受渲染路径切换影响。
 * 样式参数（宽度倍率、流光、辉光）对齐父模组自己的通道，保证各显示模式观感统一；
 * 通道优先级 1020–1050，排在父模组的 1000/1010 之后。</p>
 *
 * <p><b>类加载安全</b>：本类直接引用父模组的 API 类，只有在
 * {@link OGEWireVisualsBridge} 确认接口存在后才会被加载；请勿在其他路径直接引用。</p>
 */
final class OGEWireVisualsChannels {

    /** 金源能量通道 id。 */
    static final Identifier CHANNEL_ENERGY =
            Identifier.fromNamespaceAndPath(OriGoldsIndustrializedAge.MOD_ID, "oge_wireless_energy");
    /** 无线物流通道 id。 */
    static final Identifier CHANNEL_PIPES =
            Identifier.fromNamespaceAndPath(OriGoldsIndustrializedAge.MOD_ID, "oge_logistics_pipes");
    /** 志愿者绑定/预览高亮通道 id。 */
    static final Identifier CHANNEL_BIND =
            Identifier.fromNamespaceAndPath(OriGoldsIndustrializedAge.MOD_ID, "oge_volunteer_bind");
    /** 放置器投影框线通道 id。 */
    static final Identifier CHANNEL_PLACER =
            Identifier.fromNamespaceAndPath(OriGoldsIndustrializedAge.MOD_ID, "oge_placer_frames");

    // 与 OGEWirelessConnectorClient 原自绘路径同源的配色（0..1 浮点色换算成 ARGB 整数）。
    private static final int GOLD = WireVisualColor.of(255, 217, 64, 255);
    private static final int CYAN = WireVisualColor.of(64, 230, 242, 255);
    private static final int ORANGE = WireVisualColor.of(255, 153, 38, 255);
    private static final int PURPLE = WireVisualColor.of(166, 77, 242, 255);
    private static final int WHITE = WireVisualColor.of(255, 255, 255, 255);

    /** 悬停判定参数：与父模组自身通道一致（32 格内、0.6 视角阈值，同原自绘的 0.36 平方阈值）。 */
    private static final double HOVER_RANGE = 32.0;
    private static final double HOVER_THRESHOLD = 0.6;

    // ---- 样式（几何开销优先） ----
    //
    // 这里刻意把 glow 全部设成 0，是本轮性能定位后的结论，不是随手关功能：
    // 父模组的 WireVisualCanvas.wire() 每画一条线，先按 SUBDIVISION_LENGTH=2.0
    // 把线切成 ceil(线长/2)（上限 160）段丝带；glowScale>0 时<b>同样的段数再往独立的
    // glow 批次画一份更宽（宽度倍率上限 14×）的加色丝带</b>——带辉光的一条线等于两份
    // 每帧重建、且填充面积大得多的几何。对照 1.21.1 基线：同一条无线电线就是一段
    // DEBUG_LINES（2 个顶点、零填充面积）。开光影时这些加色大四边形要走逐片元光照，
    // 还会被光影的阴影通道再过一遍，正是"关光影满帧、开光影掉一半"的量级来源。
    // 线的颜色、渐变、粗细、流光、端点框、悬停高亮、呼吸框、准星框全部保留，
    // 只去掉这层额外辉光；flow 只改 UV 不增几何，保留。

    /** 金源能量电线：纯金渐变，宽 ×1、流光 1.6、无辉光、覆盖层。 */
    private static final WireVisualStyle ENERGY_LINE = WireVisualStyle.gradient(GOLD, GOLD)
            .widthScale(1.0f).flow(1.6f).glow(0.0f).alpha(0.9f).layer(WireVisualLayer.OVERLAY);

    /** 无线物流管道：起点青→终点橙渐变，其余参数同上。 */
    private static final WireVisualStyle PIPE_LINE = WireVisualStyle.gradient(CYAN, ORANGE)
            .widthScale(1.0f).flow(1.6f).glow(0.0f).alpha(0.95f).layer(WireVisualLayer.OVERLAY);

    /** 选点呼吸框（角括号 + 细线），基色按模式与起/终点区分。 */
    private static final WireVisualStyle PENDING_ENERGY = WireVisualStyle.of(GOLD)
            .widthScale(1.0f).glow(0.0f).layer(WireVisualLayer.OVERLAY);
    private static final WireVisualStyle PENDING_PIPE_START = WireVisualStyle.of(CYAN)
            .widthScale(1.0f).glow(0.0f).layer(WireVisualLayer.OVERLAY);
    private static final WireVisualStyle PENDING_PIPE_END = WireVisualStyle.of(ORANGE)
            .widthScale(1.0f).glow(0.0f).layer(WireVisualLayer.OVERLAY);

    /** 引力匣吸取范围外框：紫色细线，无流光无辉光。 */
    private static final WireVisualStyle RANGE_BOX = WireVisualStyle.hairline(PURPLE)
            .widthScale(1.0f).alpha(0.8f).layer(WireVisualLayer.OVERLAY);

    /** 准星指向方块的选点辅助白框。 */
    private static final WireVisualStyle AIM_BOX = WireVisualStyle.hairline(WHITE)
            .widthScale(1.0f).alpha(0.9f).layer(WireVisualLayer.OVERLAY);

    /** 志愿者快速绑定：附近可绑锯片台的白框（同原自绘的 0.9 透明白线）。 */
    private static final WireVisualStyle BIND_MOUNT = WireVisualStyle.hairline(WHITE)
            .widthScale(1.0f).alpha(0.9f).layer(WireVisualLayer.OVERLAY);
    /** 志愿者快速绑定：准星命中台的加粗强调白框（配合更亮的填充）。 */
    private static final WireVisualStyle BIND_AIMED = WireVisualStyle.hairline(WHITE)
            .widthScale(1.6f).alpha(1.0f).layer(WireVisualLayer.OVERLAY);
    /** 志愿者预览态：眼睛到目标格的连线（白，宽 ×1，无辉光）。 */
    private static final WireVisualStyle BIND_PREVIEW_LINE = WireVisualStyle.of(WHITE)
            .widthScale(1.0f).glow(0.0f).alpha(0.9f).layer(WireVisualLayer.OVERLAY);

    /** 放置器投影外框：已保存投影的 #c99951 细线（同原自绘配色与 0.9 透明度）。 */
    private static final WireVisualStyle PLACER_SAVED = WireVisualStyle.hairline(
            WireVisualColor.of(0xC9, 0x99, 0x51, 0xE6))
            .widthScale(1.0f).layer(WireVisualLayer.OVERLAY);
    /** 放置器投影外框：编辑中/准星预览的白色细线。 */
    private static final WireVisualStyle PLACER_EDIT = WireVisualStyle.hairline(WHITE)
            .widthScale(1.0f).alpha(0.9f).layer(WireVisualLayer.OVERLAY);

    private OGEWireVisualsChannels() {
    }

    /** 注册四条通道。仅由 {@link OGEWireVisualsBridge} 在接口探测成功后调用一次。 */
    static void registerAll() {
        WireVisuals.register(CHANNEL_ENERGY, 1020,
                OGEWireVisualsChannels::energyActive,
                OGEWireVisualsChannels::collectEnergy);
        WireVisuals.register(CHANNEL_PIPES, 1030,
                OGEWireVisualsChannels::pipesActive,
                OGEWireVisualsChannels::collectPipes);
        WireVisuals.register(CHANNEL_BIND, 1040,
                OGEWireVisualsChannels::bindActive,
                OGEWireVisualsChannels::collectBind);
        WireVisuals.register(CHANNEL_PLACER, 1050,
                OGEWireVisualsChannels::placerActive,
                OGEWireVisualsChannels::collectPlacer);
    }

    // ------------------------------------------------------------------
    // 金源能量显示模式
    // ------------------------------------------------------------------

    private static boolean energyActive(WireVisualContext ctx) {
        // 26.3 起“有没有界面开着”改由 mc.gui.screen() 判定（原 Minecraft.screen）
        return worldReady(ctx)
                && OGEWirelessConnectorClient.isDisplayModeOn()
                && OGEWirelessConnectorItemRef.isHeld(ctx.player())
                && Minecraft.getInstance().gui.screen() == null;
    }

    /**
     * 本通道回调跑在渲染线程上，而进/出世界的过渡期里 {@code mc.level}、{@code mc.player}
     * 会短暂为 null（新世界地形已在渲染、玩家实体还没到位；退世界时反之）。
     * 父模组只保证传入的 {@code ctx} 非空，不保证里面的世界与玩家非空，
     * 因此每条通道入口都先过这道闸——渲染线程抛出的异常会直接终结游戏进程。
     */
    private static boolean worldReady(WireVisualContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        return ctx != null && ctx.level() != null && ctx.player() != null
                && mc.level != null && mc.player != null;
    }

    /**
     * 链路快照 → {@code WireVisualLink} 列表的换算缓存（按游戏刻 + 源列表身份重建）。
     *
     * <p>链路数据只在服务端同步时变化，原来每帧都重新 new 一份列表和 N 个
     * {@code WireVisualLink}：120 FPS 下就是每秒 120 轮等价分配，纯垃圾。这里把它
     * 降到每刻一次；同一刻内若源列表对象被换掉（刚收到同步包）也会立刻重建，
     * 不会读到旧数据。悬停计算仍按帧用当前相机做，行为不变。</p>
     */
    private static final class LinkCache<S> {
        private List<S> source;
        private long tick = Long.MIN_VALUE;
        private List<WireVisualLink> views = List.of();

        List<WireVisualLink> get(long gameTime, List<S> src, java.util.function.Function<S, WireVisualLink> map) {
            if (src != this.source || gameTime != this.tick) {
                List<WireVisualLink> built = new ArrayList<>(src.size());
                for (S item : src) {
                    built.add(map.apply(item));
                }
                this.views = built;
                this.source = src;
                this.tick = gameTime;
            }
            return this.views;
        }
    }

    private static final LinkCache<OGEWirelessLinkSyncPayload.LinkView> ENERGY_CACHE = new LinkCache<>();
    private static final LinkCache<OGELogisticsPipeSyncPayload.PipeView> PIPE_CACHE = new LinkCache<>();

    private static void collectEnergy(WireVisualContext ctx) {
        WireVisualCanvas canvas = ctx.canvas();

        List<WireVisualLink> views = ENERGY_CACHE.get(ctx.level().getGameTime(),
                OGEWirelessConnectorClient.linksView(),
                link -> WireVisualLink.of(link.id(),
                        link.dimA(), BlockPos.of(link.posA()),
                        link.dimB(), BlockPos.of(link.posB()),
                        ENERGY_LINE));

        UUID hovered = WireVisualLinks.hover(ctx, views, HOVER_RANGE, HOVER_THRESHOLD);
        OGEWirelessConnectorClient.setHoveredLink(hovered);
        // nodeStyle 传 null：端点框颜色自动采样线色（金色）；悬停高亮由 render 处理。
        // endpoint() 已提供端点填充；不再对每个端点额外重复绘制第二组六面填充。
        WireVisualLinks.render(ctx, views, ENERGY_LINE, null, hovered);

        OGEWirelessSelectSyncPayload sp = OGEWirelessConnectorClient.selectedPointView();
        if (sp != null && sp.present() && sp.dim().equals(ctx.dimension())) {
            drawPending(canvas, ctx, BlockPos.of(sp.pos()), PENDING_ENERGY, GOLD);
        }

        drawAimedBlock(canvas, ctx);
    }

    // ------------------------------------------------------------------
    // 无线物流显示模式
    // ------------------------------------------------------------------

    private static boolean pipesActive(WireVisualContext ctx) {
        // 不检查手持：引力匣范围外框在物流模式开着时始终显示（与原自绘一致）。
        // 26.3 起“有没有界面开着”改由 mc.gui.screen() 判定（原 Minecraft.screen）
        return worldReady(ctx)
                && OGEWirelessConnectorClient.isLogisticsModeOn()
                && Minecraft.getInstance().gui.screen() == null;
    }

    private static void collectPipes(WireVisualContext ctx) {
        WireVisualCanvas canvas = ctx.canvas();

        drawCassetteRanges(Minecraft.getInstance(), canvas);

        if (!OGEWirelessConnectorItemRef.isHeld(ctx.player())) {
            OGEWirelessConnectorClient.setHoveredPipe(null);
            return;
        }

        List<WireVisualLink> views = PIPE_CACHE.get(ctx.level().getGameTime(),
                OGEWirelessConnectorClient.pipesView(),
                pipe -> WireVisualLink.of(pipe.id(),
                        pipe.dimFrom(), BlockPos.of(pipe.posFrom()),
                        pipe.dimTo(), BlockPos.of(pipe.posTo()),
                        PIPE_LINE));

        UUID hovered = WireVisualLinks.hover(ctx, views, HOVER_RANGE, HOVER_THRESHOLD);
        OGEWirelessConnectorClient.setHoveredPipe(hovered);
        // nodeStyle 传 null：起点端框采样渐变起点色（青）、终点端框采样终点色（橙），
        // 与原自绘的配色规则一致；endpoint() 已处理端点填充，不再重复画第二层六面填充。
        WireVisualLinks.render(ctx, views, PIPE_LINE, null, hovered);

        OGELogisticsSelectSyncPayload sp = OGEWirelessConnectorClient.selectedPipePointView();
        if (sp != null && sp.present() && sp.dim().equals(ctx.dimension())) {
            drawPending(canvas, ctx, BlockPos.of(sp.pos()),
                    sp.start() ? PENDING_PIPE_START : PENDING_PIPE_END,
                    sp.start() ? CYAN : ORANGE);
        }

        drawAimedBlock(canvas, ctx);
    }

    // ------------------------------------------------------------------
    // 志愿者快速绑定 / 预览高亮
    // ------------------------------------------------------------------

    private static boolean bindActive(WireVisualContext ctx) {
        return worldReady(ctx)
                && OGEVolunteerBindClient.hasVisuals()
                // 与原自绘路径同语义：快速绑定态开着界面时不画，预览连线不受此限。
                && (!OGEVolunteerBindClient.isQuickBindActive()
                        || Minecraft.getInstance().gui.screen() == null);
    }

    /**
     * 快速绑定与预览两种态共用一条通道：数据全部取 {@link OGEVolunteerBindClient}
     * 的刻级快照（主线程每刻刷好），每帧零次世界访问。
     */
    private static void collectBind(WireVisualContext ctx) {
        WireVisualCanvas canvas = ctx.canvas();
        List<BlockPos> mounts = OGEVolunteerBindClient.mountSnapshot();
        BlockPos aimed = OGEVolunteerBindClient.aimedMountSnapshot();
        Set<Long> bound = OGEVolunteerBindClient.boundSnapshot();

        for (BlockPos pos : mounts) {
            if (pos.equals(aimed)) {
                canvas.boxOutline(pos, BIND_AIMED);
                canvas.boxFill(pos, WireVisualColor.withAlpha(WHITE, 0.5f), WireVisualLayer.OVERLAY);
            } else {
                canvas.boxOutline(pos, BIND_MOUNT);
                if (bound.contains(pos.asLong())) {
                    canvas.boxFill(pos, WireVisualColor.withAlpha(WHITE, 0.25f), WireVisualLayer.OVERLAY);
                }
            }
        }

        Vec3 eye = OGEVolunteerBindClient.previewEyeSnapshot();
        if (eye != null) {
            BlockPos target = OGEVolunteerBindClient.previewTargetPos();
            canvas.wire(eye, Vec3.atCenterOf(target), BIND_PREVIEW_LINE);
            canvas.boxOutline(target, BIND_MOUNT);
            canvas.boxFill(target, WireVisualColor.withAlpha(WHITE, 0.25f), WireVisualLayer.OVERLAY);
        }
    }

    // ------------------------------------------------------------------
    // 金源放置器投影外框线
    // ------------------------------------------------------------------

    private static boolean placerActive(WireVisualContext ctx) {
        // 快照非空才开通道，避免空转 collect。
        return worldReady(ctx)
                && !OGEPlacerGhosts.renderSnapshot().isEmpty()
                && Minecraft.getInstance().gui.screen() == null;
    }

    /**
     * 已保存投影画 #c99951 外框、编辑中/预览画白色外框，配色与原自绘一致。
     * 半透明方块虚影不是"方块高亮"，仍由 {@link OGEPlacerRenderer} 走方块模型管线绘制。
     *
     * <p>本回调跑在渲染线程上，投影一律取 {@link OGEPlacerGhosts#renderSnapshot()}
     * 的刻级快照，每帧零次世界访问。</p>
     */
    private static void collectPlacer(WireVisualContext ctx) {
        WireVisualCanvas canvas = ctx.canvas();
        for (OGEPlacerGhosts.RenderGhost ghost : OGEPlacerGhosts.renderSnapshot()) {
            canvas.boxOutline(ghost.pos(),
                    ghost.source() == OGEPlacerGhosts.Source.SAVED ? PLACER_SAVED : PLACER_EDIT);
        }
    }

    // ------------------------------------------------------------------
    // 共用绘制件
    // ------------------------------------------------------------------

    /** 选点呼吸框：角括号 + 半透明填充，透明度随时间起伏（父模组同款节奏）。 */
    private static void drawPending(WireVisualCanvas canvas, WireVisualContext ctx,
                                    BlockPos pos, WireVisualStyle base, int rgb) {
        float breathe = ctx.breathe(2.0f, 0.0f);
        canvas.cornerBrackets(pos, base.alpha(0.35f + 0.55f * breathe), 0.3f);
        canvas.boxFill(pos, WireVisualColor.withAlpha(rgb, 0.1f + 0.35f * breathe), WireVisualLayer.OVERLAY);
    }

    /** 准星指向方块的白色选点辅助框（任一模式手持装置时显示，与原自绘一致）。 */
    private static void drawAimedBlock(WireVisualCanvas canvas, WireVisualContext ctx) {
        BlockPos aimed = OGEWirelessConnectorClient.aimedBlock(Minecraft.getInstance(), ctx.player());
        if (aimed == null) {
            return;
        }
        canvas.boxOutline(aimed, AIM_BOX);
        canvas.boxFill(aimed, WireVisualColor.withAlpha(WHITE, 0.25f), WireVisualLayer.OVERLAY);
    }

    /**
     * 引力匣吸取范围：紫色空心外框。贴脸档（0 档）画六个紧邻格，
     * 其余档位画边长 {@code 2*半径+1} 格的大立方体。
     *
     * <p>本回调跑在渲染线程上，档位与半径一律取
     * {@link OGEWirelessConnectorClient#cassetteVisuals(Minecraft)} 的刻级快照，
     * 每帧零次世界访问（120 FPS 下原来是每帧每匣一次 {@code getBlockState}）。</p>
     */
    private static void drawCassetteRanges(Minecraft mc, WireVisualCanvas canvas) {
        for (OGEWirelessConnectorClient.CassetteVisual cassette
                : OGEWirelessConnectorClient.cassetteVisuals(mc)) {
            BlockPos pos = cassette.pos();
            if (cassette.nearTier()) {
                for (Direction direction : Direction.values()) {
                    canvas.boxOutline(pos.relative(direction), RANGE_BOX);
                }
            } else {
                int radius = cassette.radius();
                AABB box = new AABB(
                        pos.getX() - radius, pos.getY() - radius, pos.getZ() - radius,
                        pos.getX() + radius + 1.0, pos.getY() + radius + 1.0, pos.getZ() + radius + 1.0);
                canvas.boxOutline(box, RANGE_BOX);
            }
        }
    }
}
