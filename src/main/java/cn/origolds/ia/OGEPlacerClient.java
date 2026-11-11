package cn.origolds.ia;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 金源放置器的客户端交互与「投影放置模式」编辑状态。
 *
 * <p>Alt / Ctrl 不是原版交互包会携带的修饰键，服务端读不到键盘，所以这里判定修饰键
 * 与准星指向，再把操作打成 {@link OGEPlacerPayload} 发给服务端复核。按键分工：</p>
 * <ul>
 *   <li><b>Alt+中键</b>：绑定主手方块 / 空手清空绑定；</li>
 *   <li><b>空手 Alt+左键</b>：对着放置器进入投影放置模式；</li>
 *   <li><b>投影模式中右键</b>：按正常放置规则算出方块状态（会替换草藤蔓、可以叠在别的
 *       投影上，摆放合法性用 {@code canSurvive} 校验），放进待保存列表并播放放置音效；</li>
 *   <li><b>投影模式中左键</b>：删掉准星命中的投影（破坏音效 + 粒子）；</li>
 *   <li><b>投影模式中 Alt+右键</b>：结束并把整份列表发给服务端保存；</li>
 *   <li><b>物流显示模式中左键</b>：删掉准星命中的已保存投影（限手持连接装置时）；</li>
 *   <li><b>空手 Ctrl+左键 / Ctrl+右键</b>：降低 / 提高放置间隔。</li>
 * </ul>
 *
 * <p>投影编辑完全发生在客户端，服务端只在保存/删除这一刻收一份结果，并且会重新校验
 * 范围与方块种类（见 {@link OGEPlacerBlock#saveProjections}、{@link OGEPlacerBlock#deleteProjection}）。
 * 投影的收集与射线命中在 {@link OGEPlacerGhosts}，放置规则在 {@link OGEPlacerPlacement}。</p>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, value = Dist.CLIENT)
public final class OGEPlacerClient {

    /** 允许远程操作的交互距离（与交互包一致）。 */
    public static final double INTERACT_RANGE = 8.0;

    /** 自动退出投影模式的距离平方：跑远了就不让继续编辑。 */
    private static final double EDIT_KEEP_DISTANCE_SQ = 256.0;

    /** 正在编辑投影的放置器；null 表示不在投影放置模式。 */
    private static BlockPos editingPos = null;

    /** 本次投影模式里已摆好的投影（进入时从方块实体复制一份，退出时整份保存）。 */
    private static final List<OGEPlacerBlockEntity.Projection> pending = new ArrayList<>();

    private OGEPlacerClient() {
    }

    // ------------------------------------------------------------------
    // 供渲染器读取的编辑状态
    // ------------------------------------------------------------------

    /** 当前正在编辑投影的放置器；不在投影模式时为 null。 */
    public static BlockPos editingPos() {
        return editingPos;
    }

    /** 本次投影模式里已摆好的投影（只读视图）。 */
    public static List<OGEPlacerBlockEntity.Projection> pending() {
        return pending;
    }

    /** 某台放置器是否正被本次客户端编辑投影。 */
    public static boolean isEditing(BlockPos pos) {
        return pos.equals(editingPos);
    }

    /** 是否处于投影放置模式。 */
    public static boolean isProjecting() {
        return editingPos != null;
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    /** 按下瞬间：投影模式下拦截左右键；物流显示模式左键删投影；否则处理绑定、进入投影、改档。 */
    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (event.getAction() != 1) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        // 26.3 起屏幕与覆盖层都不再挂在 Minecraft 上，改由 mc.gui 持有（原 mc.screen / mc.getOverlay()）
        if (mc.gui.screen() != null || mc.gui.overlay() != null || mc.level == null || mc.player == null) {
            return;
        }
        int button = event.getButton();
        if (button != InputConstants.MOUSE_BUTTON_LEFT && button != InputConstants.MOUSE_BUTTON_RIGHT && button != InputConstants.MOUSE_BUTTON_MIDDLE) {
            return;
        }
        LocalPlayer player = mc.player;
        boolean alt = isAlt(event);
        boolean ctrl = isCtrl(event);

        // ---- 投影放置模式：左右键都在这里消费掉，别去动真的世界 ----
        if (editingPos != null) {
            if (button == InputConstants.MOUSE_BUTTON_RIGHT && alt) {
                saveAndExit();
            } else if (button == InputConstants.MOUSE_BUTTON_RIGHT && !ctrl) {
                addProjection(mc, player);
            } else if (button == InputConstants.MOUSE_BUTTON_LEFT && !alt && !ctrl) {
                deleteAimedProjection(mc);
            }
            event.setCanceled(true);
            return;
        }

        // ---- 物流显示模式（手持连接装置）：左键删掉准星上的已保存投影 ----
        if (button == InputConstants.MOUSE_BUTTON_LEFT && !alt && !ctrl && OGEPlacerGhosts.logisticsVisible(mc)
                && deleteAimedProjection(mc)) {
            event.setCanceled(true);
            return;
        }

        BlockHitResult hit = aimedPlacer(mc, player);
        if (hit == null) {
            return;
        }
        if (alt && !ctrl) {
            if (button == InputConstants.MOUSE_BUTTON_MIDDLE) {
                // 绑定：主手方块；空手则清空。
                ItemStack held = player.getMainHandItem();
                if (held.isEmpty() || held.getItem() instanceof BlockItem) {
                    ClientPacketDistributor.sendToServer(new OGEPlacerPayload(
                            hit.getBlockPos().asLong(), OGEPlacerPayload.ACTION_BIND, 0, List.of()));
                    event.setCanceled(true);
                }
            } else if (button == InputConstants.MOUSE_BUTTON_LEFT && player.getMainHandItem().isEmpty()) {
                enterProjectionMode(mc, hit);
                event.setCanceled(true);
            }
        } else if (ctrl && !alt && player.getMainHandItem().isEmpty()) {
            int delta = button == InputConstants.MOUSE_BUTTON_LEFT ? -1 : (button == InputConstants.MOUSE_BUTTON_RIGHT ? 1 : 0);
            if (delta != 0) {
                ClientPacketDistributor.sendToServer(new OGEPlacerPayload(
                        hit.getBlockPos().asLong(), OGEPlacerPayload.ACTION_ADJUST, delta, List.of()));
                event.setCanceled(true);
            }
        }
    }

    /** 按住不放时每刻重复的攻击/使用调用：同样吃掉，避免挖掉方块或触发原版交互。 */
    @SubscribeEvent
    public static void onClickInput(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack() && !event.isUseItem()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        // 26.3 起屏幕与覆盖层都不再挂在 Minecraft 上，改由 mc.gui 持有（原 mc.screen / mc.getOverlay()）
        if (mc.gui.screen() != null || mc.gui.overlay() != null || mc.level == null || mc.player == null) {
            return;
        }
        // 投影模式：什么都不做，只把左键的连续挖掘/右键的连续使用拦下。
        if (editingPos != null) {
            event.setCanceled(true);
            event.setSwingHand(false);
            return;
        }
        // 物流显示模式：准星压在可删除的投影上时，左键交给删除逻辑，别挖到它后面的方块。
        if (event.isAttack() && OGEPlacerGhosts.logisticsVisible(mc) && OGEPlacerGhosts.aimed(mc) != null) {
            event.setCanceled(true);
            event.setSwingHand(false);
            return;
        }
        if (!isModifierHeld()) {
            return;
        }
        if (aimedPlacer(mc, mc.player) == null) {
            return;
        }
        event.setCanceled(true);
        event.setSwingHand(false);
    }

    /** 投影模式下跑远或方块没了就自动退出（不保存，避免误存半成品）。 */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        // 渲染线程只读这份刻级快照，所以无论是否在投影编辑模式都要每刻刷新。
        OGEPlacerGhosts.refreshRenderSnapshot(mc);
        if (editingPos == null) {
            return;
        }
        if (mc.player == null || mc.level == null) {
            clearEditing();
            return;
        }
        if (!(mc.level.getBlockEntity(editingPos) instanceof OGEPlacerBlockEntity placer)
                || placer.getBound().isEmpty()
                || mc.player.distanceToSqr(
                        editingPos.getX() + 0.5, editingPos.getY() + 0.5, editingPos.getZ() + 0.5)
                        > EDIT_KEEP_DISTANCE_SQ) {
            clearEditing();
        }
    }

    // ------------------------------------------------------------------
    // 投影编辑
    // ------------------------------------------------------------------

    private static void enterProjectionMode(Minecraft mc, BlockHitResult hit) {
        if (!(mc.level.getBlockEntity(hit.getBlockPos()) instanceof OGEPlacerBlockEntity placer)) {
            return;
        }
        if (placer.getBound().isEmpty()) {
            // 未绑定方块时没有可投影的样本，直接提示一下。
            mc.player.sendOverlayMessage(
                    Component.translatable("message.origolds_ia.placer_need_bind"));
            return;
        }
        editingPos = hit.getBlockPos();
        pending.clear();
        pending.addAll(placer.getProjections());
        mc.player.sendOverlayMessage(
                Component.translatable("message.origolds_ia.placer_project_start", pending.size()));
    }

    /**
     * 在准星指向的位置放一条投影。
     *
     * <p>目标可以是空气、可替换方块（草、藤蔓等）或别的投影；能放哪一格、放成什么状态
     * 由 {@link OGEPlacerPlacement} 按原版放置规则算出，不合法就不放。放成功后播放
     * 该方块自己的放置音效。</p>
     */
    private static void addProjection(Minecraft mc, LocalPlayer player) {
        BlockPos placerPos = editingPos;
        if (placerPos == null) {
            return;
        }
        if (!(mc.level.getBlockEntity(placerPos) instanceof OGEPlacerBlockEntity placer)) {
            clearEditing();
            return;
        }
        ItemStack bound = placer.getBound();
        if (bound.isEmpty()) {
            return;
        }
        List<OGEPlacerGhosts.Ghost> ghosts = OGEPlacerGhosts.existing(mc);
        OGEPlacerPlacement.Aim aim = OGEPlacerGhosts.aim(mc, ghosts);
        if (aim == null) {
            return;
        }
        OGEPlacerPlacement.Result result =
                OGEPlacerPlacement.resolve(mc.level, player, bound, aim, ghosts);
        if (result == null || !OGEPlacerBlock.inProjectionRange(placerPos, result.target())) {
            return;
        }
        // 同一格只留一条；重复放置就替换成新状态（朝向可能变了）。
        pending.removeIf(projection -> projection.pos().equals(result.target()));
        pending.add(new OGEPlacerBlockEntity.Projection(result.target(), result.state()));
        playPlaceSound(mc, result.target(), result.state(), player);
    }

    /**
     * 左键删除准星命中的投影：编辑中的直接从暂存列表删掉，已保存的发报文让服务端删。
     * 删除成功才返回 true。
     */
    private static boolean deleteAimedProjection(Minecraft mc) {
        OGEPlacerGhosts.Ghost ghost = OGEPlacerGhosts.aimed(mc);
        if (ghost == null) {
            return false;
        }
        if (ghost.source() == OGEPlacerGhosts.Source.PENDING) {
            pending.removeIf(projection -> projection.pos().equals(ghost.pos()));
            playBreakEffect(mc, ghost.pos(), ghost.state());
            return true;
        }
        if (ghost.source() == OGEPlacerGhosts.Source.SAVED) {
            // 效果由服务端统一广播（2001 方块事件），避免本地与服务端各响一次。
            ClientPacketDistributor.sendToServer(new OGEPlacerPayload(
                    ghost.placer().asLong(), OGEPlacerPayload.ACTION_DELETE, 0,
                    List.of(OGEPlacerPayload.Entry.of(ghost.pos(), ghost.state()))));
            return true;
        }
        return false;
    }

    /** 结束投影模式并把整份列表发给服务端保存。 */
    private static void saveAndExit() {
        if (editingPos == null) {
            return;
        }
        List<OGEPlacerPayload.Entry> entries = new ArrayList<>(pending.size());
        for (OGEPlacerBlockEntity.Projection projection : pending) {
            entries.add(OGEPlacerPayload.Entry.of(projection.pos(), projection.state()));
        }
        ClientPacketDistributor.sendToServer(new OGEPlacerPayload(
                editingPos.asLong(), OGEPlacerPayload.ACTION_SAVE, 0, entries));
        clearEditing();
    }

    private static void clearEditing() {
        editingPos = null;
        pending.clear();
    }

    /** 播放一次该方块的放置音效（只在本地客户端）。 */
    private static void playPlaceSound(Minecraft mc, BlockPos pos, BlockState state, LocalPlayer player) {
        SoundType sound = state.getSoundType(mc.level, pos, player);
        mc.level.playLocalSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                sound.getPlaceSound(), SoundSource.BLOCKS,
                (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F, false);
    }

    /** 播放一次该方块的破坏音效与粒子；2001 号方块事件就是原版挖掉方块时的表现。 */
    private static void playBreakEffect(Minecraft mc, BlockPos pos, BlockState state) {
        mc.level.levelEvent(2001, pos, Block.getId(state));
    }

    /** 空手 Alt+左键的准星目标：必须是放置器且在交互距离内。 */
    private static BlockHitResult aimedPlacer(Minecraft mc, LocalPlayer player) {
        if (!(mc.hitResult instanceof BlockHitResult hit)
                || mc.level.getBlockState(hit.getBlockPos()).getBlock() != ModBlocks.ORIGOLD_PLACER.get()) {
            return null;
        }
        if (player.getEyePosition().distanceToSqr(
                hit.getBlockPos().getX() + 0.5, hit.getBlockPos().getY() + 0.5, hit.getBlockPos().getZ() + 0.5)
                > INTERACT_RANGE * INTERACT_RANGE) {
            return null;
        }
        return hit;
    }

    /** 是否按住 Ctrl 或 Alt（物理键兜底，用于“按住不放”的拦截）。 */
    private static boolean isModifierHeld() {
        return isAltHeld() || isCtrlHeld();
    }

    private static boolean isAlt(InputEvent.MouseButton.Pre event) {
        return (event.getModifiers() & InputConstants.MOD_ALT) != 0 || isAltHeld();
    }

    private static boolean isCtrl(InputEvent.MouseButton.Pre event) {
        return (event.getModifiers() & InputConstants.MOD_CONTROL) != 0 || isCtrlHeld();
    }

    private static boolean isAltHeld() {
        return Minecraft.getInstance().hasAltDown();
    }

    private static boolean isCtrlHeld() {
        return Minecraft.getInstance().hasControlDown();
    }
}
