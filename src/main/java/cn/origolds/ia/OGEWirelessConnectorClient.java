package cn.origolds.ia;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.UUID;

/**
 * 金源无线连接装置的客户端逻辑。
 *
 * <p>能力挂在父模组（origolds_ar）原有的“金源无线红石连接装置”上：
 * 该装置用 Shift 做无线红石，本模组用 Ctrl（金源能量）与 Alt（无线物流）。</p>
 *
 * <p><b>金源能量（Ctrl）：</b>Ctrl+左键选 A、Ctrl+右键选 B 建立双向无线电线；
 * Ctrl+中键切换“金源能量显示模式”。该模式开启时：绘制金色连线与端点框、悬停高亮、
 * Delete 删除悬停连线、准星下方显示本模组方块的工作状态与缓存能量信息；
 * 该模式关闭时以上相关功能全部不可用。三模式互斥——本模式开启时自动关闭父模组
 * “线路显示模式”与本模组“无线物流显示模式”，反之父模式开启时本模式被自动关闭；
 * 开关状态写入客户端配置，重启游戏后恢复。</p>
 *
 * <p><b>无线物流（Alt）：</b>Alt+左键选起点、Alt+右键选终点，创建一条<b>单向</b>
 * 无线物流管道；Alt+中键切换“物流显示模式”。该模式开启时：绘制管道连线
 * （起点青色、终点橙色、线体青→橙渐变）与端点框、悬停高亮（仅边框变白、填充不变）、
 * Delete 删除悬停管道、Backspace 反转悬停管道方向；关闭时以上功能不可用。
 * 同样与其余两个模式互斥。</p>
 *
 * <p>手持该物品时按 Ctrl+Z / Ctrl+Y 撤销或重做最近一次装置操作
 * （能量与物流共用一条操作时间线；按开启的模式过滤类别）。</p>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class OGEWirelessConnectorClient {

    private static final double INTERACT_RANGE = 32.0;

    // 金色（金源能量主题色）
    private static final float GOLD_R = 1.0f;
    private static final float GOLD_G = 0.85f;
    private static final float GOLD_B = 0.25f;

    // 青色（无线物流起点主题色）与橙色（终点主题色）
    private static final float CYAN_R = 0.25f;
    private static final float CYAN_G = 0.9f;
    private static final float CYAN_B = 0.95f;
    private static final float ORANGE_R = 1.0f;
    private static final float ORANGE_G = 0.6f;
    private static final float ORANGE_B = 0.15f;

    // 紫色（引力匣吸取范围外框）
    private static final float PURPLE_R = 0.65f;
    private static final float PURPLE_G = 0.30f;
    private static final float PURPLE_B = 0.95f;

    // GLFW 键码（InputConstants.isKeyDown / InputEvent.Key 使用）
    private static final int GLFW_KEY_DELETE = 261;
    private static final int GLFW_KEY_BACKSPACE = 259;
    private static final int GLFW_KEY_Z = 90;
    private static final int GLFW_KEY_Y = 89;
    private static final int GLFW_KEY_LEFT_CTRL = 341;
    private static final int GLFW_KEY_RIGHT_CTRL = 345;
    private static final int GLFW_KEY_LEFT_ALT = 342;
    private static final int GLFW_KEY_RIGHT_ALT = 346;

    // 文字颜色
    private static final int COLOR_GREEN = 0xFF55FF55;
    private static final int COLOR_RED = 0xFFFF5555;
    private static final int COLOR_WHITE = 0xFFFFFFFF;

    /** 合并后的“金源能量显示模式”开关；初始值从客户端配置恢复。 */
    private static boolean displayMode = OGEClientPrefs.loadDisplayMode();

    /** “物流显示模式”开关（基础按键 Alt）；初始值从客户端配置恢复。 */
    private static boolean logisticsMode = OGEClientPrefs.loadLogisticsMode();

    static {
        // 旧配置文件可能把两个模式都存成了开启——启动时按互斥规则强制纠正
        if (displayMode && logisticsMode) {
            logisticsMode = false;
            OGEClientPrefs.saveLogisticsMode(false);
        }
    }

    private static List<OGEWirelessLinkSyncPayload.LinkView> links = List.of();
    private static OGEWirelessSelectSyncPayload selectedPoint = null;
    private static UUID hoveredLink = null;

    private static List<OGELogisticsPipeSyncPayload.PipeView> pipes = List.of();
    private static OGELogisticsSelectSyncPayload selectedPipePoint = null;
    private static UUID hoveredPipe = null;

    /** 物流显示模式下，附近引力匣吸取范围外框的位置缓存（每游戏刻重扫一次）。 */
    private static List<BlockPos> cassettePositions = List.of();
    private static long cassetteCacheTick = Long.MIN_VALUE;
    private static Level cassetteCacheLevel;

    private static boolean deleteKeyHeld = false;
    private static boolean backspaceKeyHeld = false;

    /** 互斥桥接解析失败时的一次性提示标志（每会话只提示一次）。 */
    private static boolean mutualExclusionWarned = false;

    private static final int[][] BOX_EDGES = new int[][]{{0, 1}, {1, 3}, {3, 2}, {2, 0}, {4, 5}, {5, 7}, {7, 6}, {6, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
    private static final double[][] BOX_CORNERS = new double[][]{{0.0, 0.0, 0.0}, {1.0, 0.0, 0.0}, {0.0, 1.0, 0.0}, {1.0, 1.0, 0.0}, {0.0, 0.0, 1.0}, {1.0, 0.0, 1.0}, {0.0, 1.0, 1.0}, {1.0, 1.0, 1.0}};
    private static final int[][] BOX_FACES = new int[][]{{0, 1, 3, 2}, {4, 6, 7, 5}, {0, 2, 6, 4}, {1, 5, 7, 3}, {0, 4, 5, 1}, {2, 3, 7, 6}};

    private OGEWirelessConnectorClient() {
    }

    /** 供父模组（origolds_ar）等外部读取：本模组“金源能量显示模式”是否开启。 */
    public static boolean isDisplayModeOn() {
        return displayMode;
    }

    /** 供外部读取：本模组“物流显示模式”是否开启。 */
    public static boolean isLogisticsModeOn() {
        return logisticsMode;
    }

    // ------------------------------------------------------------------
    // 网络同步回调
    // ------------------------------------------------------------------

    public static void onLinkSync(OGEWirelessLinkSyncPayload payload) {
        links = payload.links();
    }

    public static void onSelectSync(OGEWirelessSelectSyncPayload payload) {
        selectedPoint = payload;
    }

    public static void onPipeSync(OGELogisticsPipeSyncPayload payload) {
        pipes = payload.pipes();
    }

    public static void onPipeSelectSync(OGELogisticsSelectSyncPayload payload) {
        selectedPipePoint = payload;
    }

    // ------------------------------------------------------------------
    // 输入：Ctrl/Alt + 左/右键选点、中键切换两个显示模式
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (event.getAction() != 1) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.getOverlay() != null) {
            return;
        }
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        if (!OGEWirelessConnectorItemRef.isHeld(player)) {
            return;
        }
        boolean ctrl = isCtrl(event);
        boolean alt = isAlt(event);
        // Ctrl 系：金源能量（Ctrl+Alt 同时按下不响应，避免误触）
        if (ctrl && !alt) {
            if (event.getButton() == 0) {
                selectEnergy(player, true, event);
            } else if (event.getButton() == 1) {
                selectEnergy(player, false, event);
            } else if (event.getButton() == 2) {
                toggleDisplayMode(player, event);
            }
        }
        // Alt 系：无线物流
        else if (alt && !ctrl) {
            if (event.getButton() == 0) {
                selectPipe(player, true, event);
            } else if (event.getButton() == 1) {
                selectPipe(player, false, event);
            } else if (event.getButton() == 2) {
                toggleLogisticsMode(player, event);
            }
        }
    }

    private static void selectEnergy(LocalPlayer player, boolean start, InputEvent.MouseButton.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        BlockHitResult hit = raycastBlock(mc, player, INTERACT_RANGE);
        if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
            PacketDistributor.sendToServer(new OGEWirelessSelectPayload(hit.getBlockPos().asLong(), start));
            event.setCanceled(true);
        }
    }

    private static void selectPipe(LocalPlayer player, boolean start, InputEvent.MouseButton.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        BlockHitResult hit = raycastBlock(mc, player, INTERACT_RANGE);
        if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
            PacketDistributor.sendToServer(new OGEPipeSelectPayload(hit.getBlockPos().asLong(), start));
            event.setCanceled(true);
        }
    }

    private static void toggleDisplayMode(LocalPlayer player, InputEvent.MouseButton.Pre event) {
        // 开启前记录：是否已有别的模式开着（本模组物流模式或父模组红石线路模式）。
        // 若有，开启提示改用“已切换至金源能量显示模式”；动作栏会自动顶掉旧提示，
        // 父模组自己播报的开关消息无需处理。
        boolean otherWasOn = logisticsMode || OrigoldsArBridge.isParentLineDisplayOn();
        displayMode = !displayMode;
        if (displayMode) {
            // 互斥：开启任一模式时关闭其余全部模式——
            // 关掉本模组“无线物流显示模式”与父模组（origolds_ar）的“线路显示模式”
            logisticsMode = false;
            OGEClientPrefs.saveLogisticsMode(false);
            hoveredPipe = null;
            OrigoldsArBridge.turnOffParentLineDisplay();
        } else {
            hoveredLink = null;
        }
        OGEClientPrefs.saveDisplayMode(displayMode);
        player.displayClientMessage(Component.translatable(
                displayMode
                        ? (otherWasOn ? "message.origolds_ia.wc_switched_to_display"
                                       : "message.origolds_ia.wc_display_on")
                        : "message.origolds_ia.wc_display_off"), true);
        event.setCanceled(true);
    }

    private static void toggleLogisticsMode(LocalPlayer player, InputEvent.MouseButton.Pre event) {
        // 同 toggleDisplayMode：从别的模式切过来时提示“已切换至物流显示模式”
        boolean otherWasOn = displayMode || OrigoldsArBridge.isParentLineDisplayOn();
        logisticsMode = !logisticsMode;
        if (logisticsMode) {
            // 互斥：开启任一模式时关闭其余全部模式——
            // 关掉本模组“金源能量显示模式”与父模组（origolds_ar）的“线路显示模式”
            displayMode = false;
            OGEClientPrefs.saveDisplayMode(false);
            hoveredLink = null;
            OrigoldsArBridge.turnOffParentLineDisplay();
        } else {
            hoveredPipe = null;
        }
        OGEClientPrefs.saveLogisticsMode(logisticsMode);
        player.displayClientMessage(Component.translatable(
                logisticsMode
                        ? (otherWasOn ? "message.origolds_ia.wc_switched_to_logistics"
                                       : "message.origolds_ia.wc_logistics_on")
                        : "message.origolds_ia.wc_logistics_off"), true);
        event.setCanceled(true);
    }

    /** 是否按住 Ctrl（GLFW 修饰位 + 物理键兜底）。 */
    private static boolean isCtrl(InputEvent.MouseButton.Pre event) {
        if ((event.getModifiers() & 2) != 0) {
            return true;
        }
        long window = Minecraft.getInstance().getWindow().getWindow();
        return InputConstants.isKeyDown(window, GLFW_KEY_LEFT_CTRL) || InputConstants.isKeyDown(window, GLFW_KEY_RIGHT_CTRL);
    }

    /** 是否按住 Alt（GLFW 修饰位 + 物理键兜底）。 */
    private static boolean isAlt(InputEvent.MouseButton.Pre event) {
        if ((event.getModifiers() & 4) != 0) {
            return true;
        }
        long window = Minecraft.getInstance().getWindow().getWindow();
        return InputConstants.isKeyDown(window, GLFW_KEY_LEFT_ALT) || InputConstants.isKeyDown(window, GLFW_KEY_RIGHT_ALT);
    }

    // ------------------------------------------------------------------
    // 键盘：Ctrl+Z 撤销、Ctrl+Y 重做（任一显示模式开启时可用；两模式都开则撤销最近一次操作）
    // ------------------------------------------------------------------

    /**
     * Ctrl+Z / Ctrl+Y 撤销重做。
     *
     * <p>父模组（origolds_ar）的客户端监听器同样响应 Ctrl+Z/Ctrl+Y（手持该装置时
     * 无条件发它自己的撤销包），且它的失败提示文案与本模组历史上完全相同，
     * 导致本模组的撤销结果被父模组“当前手持没有可撤销的操作”顶掉。
     * 对策：①本模组提示文案加“金源装置：”前缀区分；②服务端把撤销结果
     * 延迟一个游戏刻播报（见 {@code OGEWirelessHistory}），父模组的消息先到、
     * 我们的后到，动作栏最终显示的是本模组的真实结果。</p>
     */
    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        if (event.getAction() != 1 || (event.getKey() != GLFW_KEY_Z && event.getKey() != GLFW_KEY_Y)) {
            return;
        }
        if ((event.getModifiers() & 2) == 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null || mc.getOverlay() != null) {
            return;
        }
        if (!OGEWirelessConnectorItemRef.isHeld(mc.player)) {
            return;
        }
        int scope;
        if (displayMode && logisticsMode) {
            scope = OGEWirelessHistory.SCOPE_ANY;
        } else if (displayMode) {
            scope = OGEWirelessHistory.SCOPE_ENERGY;
        } else if (logisticsMode) {
            scope = OGEWirelessHistory.SCOPE_LOGISTICS;
        } else {
            // 两个显示模式都没开：仍按提示承诺响应 Ctrl+Z（“手持时可按Ctrl+Z撤销”），
            // 撤销共用时间线上最近一次操作，不按类别过滤。
            scope = OGEWirelessHistory.SCOPE_ANY;
        }
        PacketDistributor.sendToServer(new OGEWirelessUndoPayload(event.getKey() == GLFW_KEY_Z, scope));
    }

    // ------------------------------------------------------------------
    // 每一客户端刻：Delete 删除悬停对象、Backspace 反转悬停管道（各自受模式门控）
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) {
            return;
        }
        // 互斥联动：父模组（origolds_ar）的“无线红石线路显示模式”一旦被开启，
        // 关掉本模组的金源能量与无线物流两个显示模式，三模式同时只有一个生效。
        // 被顶掉关闭时静默处理、不播报“…：关”——那两条提示只在玩家自己再次按
        // 对应快捷键关闭模式时才出现。
        if (OrigoldsArBridge.isParentLineDisplayOn() && (displayMode || logisticsMode)) {
            displayMode = false;
            logisticsMode = false;
            hoveredLink = null;
            hoveredPipe = null;
            OGEClientPrefs.saveDisplayMode(false);
            OGEClientPrefs.saveLogisticsMode(false);
        }
        // 一次性诊断：父模组在、本模组模式开着，但桥接解析失败（互斥联动无法生效）。
        if (!mutualExclusionWarned && (displayMode || logisticsMode)
                && OrigoldsArBridge.isParentModLoaded() && !OrigoldsArBridge.isAvailable()) {
            mutualExclusionWarned = true;
            mc.player.displayClientMessage(
                    Component.translatable("message.origolds_ia.wc_mutual_unavailable"), false);
        }
        long window = mc.getWindow().getWindow();
        boolean holds = OGEWirelessConnectorItemRef.isHeld(mc.player);

        boolean deleteDown = InputConstants.isKeyDown(window, GLFW_KEY_DELETE);
        if (deleteDown && !deleteKeyHeld && holds) {
            if (displayMode && hoveredLink != null) {
                PacketDistributor.sendToServer(new OGEWirelessDeletePayload(hoveredLink));
                hoveredLink = null;
            }
            if (logisticsMode && hoveredPipe != null) {
                PacketDistributor.sendToServer(new OGEPipeDeletePayload(hoveredPipe));
                hoveredPipe = null;
            }
        }
        deleteKeyHeld = deleteDown;

        boolean backspaceDown = InputConstants.isKeyDown(window, GLFW_KEY_BACKSPACE);
        if (backspaceDown && !backspaceKeyHeld && holds
                && logisticsMode && hoveredPipe != null) {
            PacketDistributor.sendToServer(new OGEPipeReversePayload(hoveredPipe));
            hoveredPipe = null;
        }
        backspaceKeyHeld = backspaceDown;
    }

    // ------------------------------------------------------------------
    // 渲染：金色电线 + 青→橙物流管道 + 高亮 + 信息浮窗（各受对应显示模式门控）
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (mc.screen != null) {
            return;
        }
        ResourceKey<Level> dim = mc.level.dimension();
        boolean holds = OGEWirelessConnectorItemRef.isHeld(mc.player);
        boolean showConnector = displayMode && holds;
        boolean showPipes = logisticsMode && holds;
        // 物流显示模式也要手持连接装置才会标出引力匣的吸取范围，和管道高亮同一门槛；
        // 只看 logisticsMode 的话，随手切换过物流模式、之后收起装置，紫色范围框还会一直挂着。
        boolean showCassetteRanges = logisticsMode && holds;
        if (!showConnector) {
            hoveredLink = null;
        }
        if (!showPipes) {
            hoveredPipe = null;
        }
        boolean showPending = showConnector && selectedPoint != null && selectedPoint.present()
                && selectedPoint.dim().equals(dim);
        boolean showPipePending = showPipes && selectedPipePoint != null && selectedPipePoint.present()
                && selectedPipePoint.dim().equals(dim);

        Vec3 cam = event.getCamera().getPosition();
        Vector3f camLook = event.getCamera().getLookVector();
        Vec3 look = new Vec3(camLook.x, camLook.y, camLook.z);
        // 准星白框：任一显示模式开启时作为选点辅助显示
        BlockPos aimed = (showConnector || showPipes) ? aimedBlock(mc, mc.player) : null;
        boolean showAimed = aimed != null;

        if (!(showConnector || showPipes || showCassetteRanges || showPending || showPipePending || showAimed)) {
            return;
        }
        if (showConnector) {
            hoveredLink = hoverNearest(energySegments(dim), cam, look);
        }
        if (showPipes) {
            hoveredPipe = hoverNearest(pipeSegments(dim), cam, look);
        }

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f matrix = poseStack.last().pose();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();

        BufferBuilder lines = Tesselator.getInstance().begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        int lineVerts = 0;
        if (showConnector) {
            for (OGEWirelessLinkSyncPayload.LinkView link : links) {
                boolean aHere = link.dimA().equals(dim);
                boolean bHere = link.dimB().equals(dim);
                Vec3 a = center(link.posA());
                Vec3 b = center(link.posB());
                boolean hovered = link.id().equals(hoveredLink);
                if (aHere && bHere) {
                    lineVerts = segment(matrix, lines, a, b,
                            hovered ? 1.0f : GOLD_R, hovered ? 1.0f : GOLD_G, hovered ? 1.0f : GOLD_B,
                            hovered ? 0.95f : 0.9f,
                            hovered ? 1.0f : GOLD_R, hovered ? 1.0f : GOLD_G, hovered ? 1.0f : GOLD_B,
                            hovered ? 0.95f : 0.9f, lineVerts);
                }
                if (aHere) {
                    lineVerts = boxEdges(matrix, lines, a,
                            hovered ? 1.0f : GOLD_R, hovered ? 1.0f : GOLD_G, hovered ? 1.0f : GOLD_B,
                            hovered ? 0.95f : 0.8f, lineVerts);
                }
                if (bHere) {
                    lineVerts = boxEdges(matrix, lines, b,
                            hovered ? 1.0f : GOLD_R, hovered ? 1.0f : GOLD_G, hovered ? 1.0f : GOLD_B,
                            hovered ? 0.95f : 0.8f, lineVerts);
                }
            }
        }
        if (showPipes) {
            for (OGELogisticsPipeSyncPayload.PipeView pipe : pipes) {
                boolean fromHere = pipe.dimFrom().equals(dim);
                boolean toHere = pipe.dimTo().equals(dim);
                Vec3 from = center(pipe.posFrom());
                Vec3 to = center(pipe.posTo());
                boolean hovered = pipe.id().equals(hoveredPipe);
                // 线体：起点青→终点橙渐变；悬停时整体提亮为白
                if (fromHere && toHere) {
                    lineVerts = segment(matrix, lines, from, to,
                            hovered ? 1.0f : CYAN_R, hovered ? 1.0f : CYAN_G, hovered ? 1.0f : CYAN_B, 0.95f,
                            hovered ? 1.0f : ORANGE_R, hovered ? 1.0f : ORANGE_G, hovered ? 1.0f : ORANGE_B, 0.95f,
                            lineVerts);
                }
                if (fromHere) {
                    lineVerts = boxEdges(matrix, lines, from,
                            hovered ? 1.0f : CYAN_R, hovered ? 1.0f : CYAN_G, hovered ? 1.0f : CYAN_B,
                            hovered ? 0.95f : 0.85f, lineVerts);
                }
                if (toHere) {
                    lineVerts = boxEdges(matrix, lines, to,
                            hovered ? 1.0f : ORANGE_R, hovered ? 1.0f : ORANGE_G, hovered ? 1.0f : ORANGE_B,
                            hovered ? 0.95f : 0.85f, lineVerts);
                }
            }
        }
        if (showCassetteRanges) {
            // 引力匣的吸取范围：紫色空心外框（只画棱，不填充），随吸附档位变化。
            for (BlockPos cassettePos : nearbyCassettes(mc)) {
                BlockState cassetteState = mc.level.getBlockState(cassettePos);
                if (!(cassetteState.getBlock() instanceof OGEGravityCassetteBlock)) {
                    continue;
                }
                if (OGEGravityCassetteBlock.isNearTier(cassetteState)) {
                    // 相邻档只作用于贴身的 6 个方块：逐格画这 6 个邻居的框，
                    // 比画成 3×3×3 的整块准确——它确实不吸斜角。
                    for (Direction direction : Direction.values()) {
                        lineVerts = boxEdges(matrix, lines, center(cassettePos.relative(direction)),
                                PURPLE_R, PURPLE_G, PURPLE_B, 0.8f, lineVerts);
                    }
                } else {
                    lineVerts = rangeBoxEdges(matrix, lines, cassettePos,
                            OGEGravityCassetteBlock.suctionRadius(cassetteState),
                            PURPLE_R, PURPLE_G, PURPLE_B, 0.8f, lineVerts);
                }
            }
        }
        if (showPending) {
            float breathe = breatheAlpha();
            lineVerts = boxEdges(matrix, lines, center(selectedPoint.pos()),
                    GOLD_R, GOLD_G, GOLD_B, 0.35f + 0.55f * breathe, lineVerts);
        }
        if (showPipePending) {
            float breathe = breatheAlpha();
            Vec3 p = center(selectedPipePoint.pos());
            if (selectedPipePoint.start()) {
                lineVerts = boxEdges(matrix, lines, p, CYAN_R, CYAN_G, CYAN_B, 0.35f + 0.55f * breathe, lineVerts);
            } else {
                lineVerts = boxEdges(matrix, lines, p, ORANGE_R, ORANGE_G, ORANGE_B, 0.35f + 0.55f * breathe, lineVerts);
            }
        }
        if (showAimed) {
            lineVerts = boxEdges(matrix, lines, center(aimed), 1.0f, 1.0f, 1.0f, 0.9f, lineVerts);
        }
        if (lineVerts > 0) {
            BufferUploader.drawWithShader(lines.buildOrThrow());
        }

        BufferBuilder quads = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        int quadVerts = 0;
        if (showConnector) {
            for (OGEWirelessLinkSyncPayload.LinkView link : links) {
                if (link.dimA().equals(dim)) {
                    quadVerts = boxFill(matrix, quads, center(link.posA()), GOLD_R, GOLD_G, GOLD_B, 0.22f, quadVerts);
                }
                if (link.dimB().equals(dim)) {
                    quadVerts = boxFill(matrix, quads, center(link.posB()), GOLD_R, GOLD_G, GOLD_B, 0.22f, quadVerts);
                }
            }
        }
        if (showPipes) {
            for (OGELogisticsPipeSyncPayload.PipeView pipe : pipes) {
                // 悬停时仅边框变白，端点方框的填充保持起点青、终点橙不变
                if (pipe.dimFrom().equals(dim)) {
                    quadVerts = boxFill(matrix, quads, center(pipe.posFrom()), CYAN_R, CYAN_G, CYAN_B, 0.22f, quadVerts);
                }
                if (pipe.dimTo().equals(dim)) {
                    quadVerts = boxFill(matrix, quads, center(pipe.posTo()), ORANGE_R, ORANGE_G, ORANGE_B, 0.22f, quadVerts);
                }
            }
        }
        if (showPending) {
            quadVerts = boxFill(matrix, quads, center(selectedPoint.pos()), GOLD_R, GOLD_G, GOLD_B,
                    0.1f + 0.35f * breatheAlpha(), quadVerts);
        }
        if (showPipePending) {
            quadVerts = selectedPipePoint.start()
                    ? boxFill(matrix, quads, center(selectedPipePoint.pos()), CYAN_R, CYAN_G, CYAN_B,
                            0.1f + 0.35f * breatheAlpha(), quadVerts)
                    : boxFill(matrix, quads, center(selectedPipePoint.pos()), ORANGE_R, ORANGE_G, ORANGE_B,
                            0.1f + 0.35f * breatheAlpha(), quadVerts);
        }
        if (showAimed) {
            quadVerts = boxFill(matrix, quads, center(aimed), 1.0f, 1.0f, 1.0f, 0.25f, quadVerts);
        }
        if (quadVerts > 0) {
            BufferUploader.drawWithShader(quads.buildOrThrow());
        }

        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!displayMode || mc.player == null || mc.level == null || mc.screen != null) {
            return;
        }
        if (!OGEWirelessConnectorItemRef.isHeld(mc.player)) {
            return;
        }
        // 每帧实时射线检测准星指向的方块，并读取客户端方块实体的每-tick 同步值，
        // 保证信息逐 tick 自动刷新；切换物品或准星离开方块时当帧即消失，不会滞留。
        BlockPos aimed = aimedBlock(mc, mc.player);
        if (aimed == null || !isOgeMachine(mc.level.getBlockState(aimed).getBlock())) {
            return;
        }
        BlockEntity be = mc.level.getBlockEntity(aimed);
        if (!(be instanceof OGEMachineBlockEntity machine)) {
            return;
        }
        InfoLabel info = buildInfo(machine);
        if (info == null) {
            return;
        }
        GuiGraphics gui = event.getGuiGraphics();
        Font font = mc.font;
        // 绝对屏幕位置：水平居中、固定在屏幕中心准星正下方。
        // 不以鼠标 OS 坐标为锚点——第一人称下鼠标被捕获，转动视角时系统光标会漂移，
        // 导致浮窗“跟随准星/方块移动”；锚定屏幕中心后位置恒为绝对值。
        int cx = mc.getWindow().getGuiScaledWidth() / 2;
        int cy = mc.getWindow().getGuiScaledHeight() / 2 + 12;
        gui.drawCenteredString(font, info.line1(), cx, cy, info.color1());
        gui.drawCenteredString(font, info.line2(), cx, cy + font.lineHeight + 2, info.color2());
    }

    // ------------------------------------------------------------------
    // 信息浮窗：仅对十字准星指向的本模组方块生成绝对位置标签
    // ------------------------------------------------------------------

    private static boolean isOgeMachine(Block block) {
        return block == ModBlocks.WIND_ORIGOLD_ENERGIA_GENERATOR.get()
                || block == ModBlocks.WATER_ORIGOLD_ENERGIA_GENERATOR.get()
                || block == ModBlocks.FIRE_ORIGOLD_ENERGIA_GENERATOR.get()
                || block == ModBlocks.MAGIC_ORIGOLD_ENERGIA_GENERATOR.get()
                || block == ModBlocks.ORIGOLD_ENERGIA_CAPACITOR.get();
    }

    /** 按方块类型生成两行信息。发电机：工作状态+产率；电池：净输出+缓存。 */
    private static InfoLabel buildInfo(OGEMachineBlockEntity machine) {
        IGoldSourceEnergyStorage storage = machine.getOGEStorage();
        int stored = storage.getOGEStored();
        int capacity = storage.getOGECapacity();
        int produce = storage.getOGEProduceRate();
        Component line2 = Component.literal("OGE " + stored + "u / " + capacity + "u");
        if (produce > 0) {
            boolean working = machine.isWorking() && stored < capacity;
            int rate = working ? produce : 0;
            // 状态词走翻译键（中英文环境各自显示），数值部分语言中立。
            Component status = Component.translatable(
                    working ? "overlay.origolds_ia.oge_working" : "overlay.origolds_ia.oge_stopped");
            Component line1 = status.copy().append(Component.literal(" OGE +" + rate + "u/s"));
            return new InfoLabel(line1, working ? COLOR_GREEN : COLOR_RED, line2, COLOR_WHITE);
        } else {
            // 净速率＝过去 3 秒内获得/失去 OGE 的平均值，每秒才更新一次；
            // 第二行缓存量仍随每 tick 的方块实体同步实时刷新。
            double net = machine.getOGENetRate();
            String key;
            int color;
            if (net > 0.0) {
                key = "overlay.origolds_ia.oge_import";
                color = COLOR_GREEN;
            } else if (net < 0.0) {
                key = "overlay.origolds_ia.oge_export";
                color = COLOR_RED;
            } else {
                key = "overlay.origolds_ia.oge_idle";
                color = COLOR_WHITE;
            }
            String rate = (net > 0.0 ? "+" : "") + formatRate(net);
            Component line1 = Component.translatable(key)
                    .append(Component.literal(" OGE " + rate + "u/s"));
            return new InfoLabel(line1, color, line2, COLOR_WHITE);
        }
    }

    /** 格式化速率，尽量显示整数，否则保留一位小数。 */
    private static String formatRate(double value) {
        double rounded = Math.round(value * 10.0) / 10.0;
        if (Math.abs(rounded - Math.rint(value)) < 0.05) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.format("%.1f", rounded);
    }

    // ------------------------------------------------------------------
    // 几何与工具
    // ------------------------------------------------------------------

    /** 一条可悬停选中的线段（同维度两端）。 */
    private record HoverSegment(UUID id, Vec3 a, Vec3 b) {
    }

    private static List<HoverSegment> energySegments(ResourceKey<Level> dim) {
        java.util.ArrayList<HoverSegment> out = new java.util.ArrayList<>();
        for (OGEWirelessLinkSyncPayload.LinkView link : links) {
            if (link.dimA().equals(dim) && link.dimB().equals(dim)) {
                out.add(new HoverSegment(link.id(), center(link.posA()), center(link.posB())));
            }
        }
        return out;
    }

    private static List<HoverSegment> pipeSegments(ResourceKey<Level> dim) {
        java.util.ArrayList<HoverSegment> out = new java.util.ArrayList<>();
        for (OGELogisticsPipeSyncPayload.PipeView pipe : pipes) {
            if (pipe.dimFrom().equals(dim) && pipe.dimTo().equals(dim)) {
                out.add(new HoverSegment(pipe.id(), center(pipe.posFrom()), center(pipe.posTo())));
            }
        }
        return out;
    }

    /** 计算玩家视线最接近哪条线段；无命中返回 {@code null}。 */
    private static UUID hoverNearest(List<HoverSegment> segments, Vec3 eye, Vec3 look) {
        UUID best = null;
        double bestDistSq = 0.36;
        double rangeSq = INTERACT_RANGE * INTERACT_RANGE;
        for (HoverSegment segment : segments) {
            Vec3 ab = segment.b().subtract(segment.a());
            double lenSq = ab.lengthSqr();
            if (lenSq < 1.0E-4) {
                continue;
            }
            int steps = (int) Math.min(64.0, Math.max(8.0, ab.length() * 2.0));
            for (int i = 0; i <= steps; ++i) {
                double t = (double) i / (double) steps;
                Vec3 p = segment.a().add(ab.x * t, ab.y * t, ab.z * t);
                Vec3 v = p.subtract(eye);
                double along = v.dot(look);
                if (along <= 0.5 || v.lengthSqr() > rangeSq) {
                    continue;
                }
                double distSq = v.lengthSqr() - along * along;
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    best = segment.id();
                }
            }
        }
        return best;
    }

    private static BlockHitResult raycastBlock(Minecraft mc, Player player, double range) {
        if (mc.level == null) {
            return null;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        return mc.level.clip(new ClipContext(eye, eye.add(look.scale(range)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
    }

    private static BlockPos aimedBlock(Minecraft mc, Player player) {
        BlockHitResult hit = raycastBlock(mc, player, INTERACT_RANGE);
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return hit.getBlockPos();
    }

    private static Vec3 center(long packedPos) {
        return Vec3.atCenterOf(BlockPos.of(packedPos));
    }

    private static Vec3 center(BlockPos pos) {
        return Vec3.atCenterOf(pos);
    }

    /** 画一条两端各自着色的线段（顶点色插值即为渐变）。 */
    private static int segment(Matrix4f m, BufferBuilder buffer, Vec3 a, Vec3 b,
                               float r0, float g0, float b0, float alpha0,
                               float r1, float g1, float b1, float alpha1, int verts) {
        buffer.addVertex(m, (float) a.x, (float) a.y, (float) a.z).setColor(r0, g0, b0, alpha0);
        buffer.addVertex(m, (float) b.x, (float) b.y, (float) b.z).setColor(r1, g1, b1, alpha1);
        return verts + 2;
    }

    private static int boxEdges(Matrix4f m, BufferBuilder buffer, Vec3 center,
                                float r, float g, float b, float alpha, int verts) {
        double x = center.x - 0.5;
        double y = center.y - 0.5;
        double z = center.z - 0.5;
        for (int[] edge : BOX_EDGES) {
            double[] p1 = BOX_CORNERS[edge[0]];
            double[] p2 = BOX_CORNERS[edge[1]];
            buffer.addVertex(m, (float) (x + p1[0]), (float) (y + p1[1]), (float) (z + p1[2])).setColor(r, g, b, alpha);
            buffer.addVertex(m, (float) (x + p2[0]), (float) (y + p2[1]), (float) (z + p2[2])).setColor(r, g, b, alpha);
            verts += 2;
        }
        return verts;
    }

    /** 画一个以方块为中心、半径 {@code radius} 的立方体外框（边长 2*radius+1 格）。 */
    private static int rangeBoxEdges(Matrix4f m, BufferBuilder buffer, BlockPos pos, int radius,
                                     float r, float g, float b, float alpha, int verts) {
        double minX = pos.getX() - radius;
        double minY = pos.getY() - radius;
        double minZ = pos.getZ() - radius;
        double maxX = pos.getX() + radius + 1.0;
        double maxY = pos.getY() + radius + 1.0;
        double maxZ = pos.getZ() + radius + 1.0;
        for (int[] edge : BOX_EDGES) {
            double[] a = BOX_CORNERS[edge[0]];
            double[] c = BOX_CORNERS[edge[1]];
            buffer.addVertex(m, (float) (a[0] == 0.0 ? minX : maxX),
                    (float) (a[1] == 0.0 ? minY : maxY), (float) (a[2] == 0.0 ? minZ : maxZ)).setColor(r, g, b, alpha);
            buffer.addVertex(m, (float) (c[0] == 0.0 ? minX : maxX),
                    (float) (c[1] == 0.0 ? minY : maxY), (float) (c[2] == 0.0 ? minZ : maxZ)).setColor(r, g, b, alpha);
            verts += 2;
        }
        return verts;
    }

    /**
     * 缓存玩家附近已加载区块里的引力匣位置；同一游戏刻内只重扫一次，
     * 避免多帧渲染反复遍历区块。
     */
    private static List<BlockPos> nearbyCassettes(Minecraft mc) {
        long now = mc.level.getGameTime();
        if (mc.level == cassetteCacheLevel && now == cassetteCacheTick) {
            return cassettePositions;
        }
        cassetteCacheLevel = mc.level;
        cassetteCacheTick = now;
        List<BlockPos> found = new java.util.ArrayList<>();
        BlockPos origin = mc.player.blockPosition();
        int radius = 48;
        int minCx = (origin.getX() - radius) >> 4;
        int maxCx = (origin.getX() + radius) >> 4;
        int minCz = (origin.getZ() - radius) >> 4;
        int maxCz = (origin.getZ() + radius) >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                LevelChunk chunk = mc.level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be instanceof OGEGravityCassetteBlockEntity) {
                        found.add(be.getBlockPos());
                    }
                }
            }
        }
        cassettePositions = found;
        return found;
    }

    private static int boxFill(Matrix4f m, BufferBuilder buffer, Vec3 center,
                               float r, float g, float b, float alpha, int verts) {
        double x = center.x - 0.5;
        double y = center.y - 0.5;
        double z = center.z - 0.5;
        for (int[] face : BOX_FACES) {
            for (int idx : face) {
                double[] p = BOX_CORNERS[idx];
                buffer.addVertex(m, (float) (x + p[0]), (float) (y + p[1]), (float) (z + p[2])).setColor(r, g, b, alpha);
                ++verts;
            }
        }
        return verts;
    }

    private static float breatheAlpha() {
        long now = System.currentTimeMillis();
        double phase = (double) (now % 2000L) / 2000.0;
        return (float) (1.0 - Math.sin(phase * Math.PI));
    }

    /** 一个方块的两行信息及其颜色。 */
    private record InfoLabel(Component line1, int color1, Component line2, int color2) {
    }
}
