package cn.origolds.ia;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 金源能量无线连线的报文注册。
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class OGEWirelessNetwork {

    private OGEWirelessNetwork() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("geo_wireless");
        registrar.playToServer(OGEWirelessSelectPayload.TYPE, OGEWirelessSelectPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        OGEWirelessManager.onSelect(player, payload.pos(), payload.start());
                    }
                }));
        registrar.playToServer(OGEWirelessDeletePayload.TYPE, OGEWirelessDeletePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        OGEWirelessManager.onDelete(player, payload.linkId());
                    }
                }));
        registrar.playToServer(OGEWirelessUndoPayload.TYPE, OGEWirelessUndoPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        OGEWirelessManager.onUndoRedo(player, payload.undo(), payload.scope());
                    }
                }));
        registrar.playToClient(OGEWirelessLinkSyncPayload.TYPE, OGEWirelessLinkSyncPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> OGEWirelessConnectorClient.onLinkSync(payload)));
        registrar.playToClient(OGEWirelessSelectSyncPayload.TYPE, OGEWirelessSelectSyncPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> OGEWirelessConnectorClient.onSelectSync(payload)));
        // ---- 无线物流管道（基础按键 Alt） ----
        registrar.playToServer(OGEPipeSelectPayload.TYPE, OGEPipeSelectPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        OGELogisticsManager.onSelect(player, payload.pos(), payload.start());
                    }
                }));
        registrar.playToServer(OGEPipeDeletePayload.TYPE, OGEPipeDeletePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        OGELogisticsManager.onDelete(player, payload.pipeId());
                    }
                }));
        registrar.playToServer(OGEPipeReversePayload.TYPE, OGEPipeReversePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        OGELogisticsManager.onReverse(player, payload.pipeId());
                    }
                }));
        registrar.playToClient(OGELogisticsPipeSyncPayload.TYPE, OGELogisticsPipeSyncPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> OGEWirelessConnectorClient.onPipeSync(payload)));
        registrar.playToClient(OGELogisticsSelectSyncPayload.TYPE, OGELogisticsSelectSyncPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> OGEWirelessConnectorClient.onPipeSelectSync(payload)));
        // ---- 虚空抽屉：同步 Shift/Ctrl/Alt 是否被按住（按住期间排序循环整体跳过） ----
        registrar.playToServer(OGEVoidDrawerPausePayload.TYPE, OGEVoidDrawerPausePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    // 只作用于发送者当前打开的抽屉菜单：菜单类型即校验，
                    // 客户端无法借这个包操作任意坐标的方块实体。
                    if (context.player() instanceof ServerPlayer player
                            && player.containerMenu instanceof OGEVoidDrawerMenu menu) {
                        menu.setModifierHeld(payload.held());
                    }
                }));
        // ---- 金源销毁站：Alt+左键降低等待延迟、Alt+右键增加等待延迟（空手） ----
        registrar.playToServer(OGEVoidingStationModePayload.TYPE, OGEVoidingStationModePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        OGEVoidingStationBlock.adjustMode(player, payload.pos(), payload.delta());
                    }
                }));
        // ---- 金源鼓风机：Ctrl+左/右键调距离档、Ctrl+Alt+左/右键调力量档（空手） ----
        registrar.playToServer(OGEBlowerGearPayload.TYPE, OGEBlowerGearPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        OGEBlowerBlock.adjustGear(player, payload.pos(),
                                payload.distanceStep(), payload.powerStep());
                    }
                }));
        // ---- 金源自动附魔机：Ctrl+左键降档、Ctrl+右键升档（空手） ----
        registrar.playToServer(OGEMechEnchanterTierPayload.TYPE, OGEMechEnchanterTierPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        OGEMechEnchanterBlock.adjustTier(player, payload.pos(), payload.delta());
                    }
                }));
        // ---- 金源引力匣：Alt+左键降档、Alt+右键升档（空手） ----
        registrar.playToServer(OGEGravityCassetteTierPayload.TYPE, OGEGravityCassetteTierPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        OGEGravityCassetteBlock.adjustTier(player, payload.pos(), payload.delta());
                    }
                }));
        // ---- 虚空抽屉 / 引力匣：Alt+中键绑定主手物品、空手 Alt+中键清空绑定 ----
        registrar.playToServer(OGEItemBindPayload.TYPE, OGEItemBindPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        OGEItemBind.handle(player, payload.pos());
                    }
                }));
        // ---- 金源自动附魔机：界面箭头按钮确认开始合并 ----
        registrar.playToServer(OGEMechEnchanterStartPayload.TYPE, OGEMechEnchanterStartPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player
                            && player.containerMenu instanceof OGEMechEnchanterMenu menu
                            && menu.getBlockEntity() != null) {
                        menu.getBlockEntity().startJob();
                    }
                }));
        // ---- 金源坷垃机：Ctrl+左键降档、Ctrl+右键升档（空手） ----
        registrar.playToServer(OGEKelaMachineTierPayload.TYPE, OGEKelaMachineTierPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        OGEKelaMachineBlock.adjustTier(player, payload.pos(), payload.delta());
                    }
                }));
        // ---- 金源放置器：Alt+中键绑定、Ctrl+左/右键改间隔、Alt+右键保存投影 ----
        registrar.playToServer(OGEPlacerPayload.TYPE, OGEPlacerPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        OGEPlacerBlock.handle(player, payload);
                    }
                }));
        // ---- 金源红石脉冲钟：界面按钮（开始/暂停/继续/重置） ----
        registrar.playToServer(OGEClockCommandPayload.TYPE, OGEClockCommandPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player
                            && player.containerMenu instanceof OGEClockMenu menu) {
                        menu.applyCommand(payload.action(), payload.ticks());
                    }
                }));
        // ---- 金源红石脉冲钟：把倒计时同步给打开界面的玩家 ----
        registrar.playToClient(OGEClockSyncPayload.TYPE, OGEClockSyncPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> OGEClockScreen.handleSync(payload)));
        // ---- 金源超级漏斗：界面按钮/文本框指令，以及把计时与上限同步回来 ----
        registrar.playToServer(OGEHyperhopperCommandPayload.TYPE, OGEHyperhopperCommandPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player
                            && player.containerMenu instanceof OGEHyperhopperMenu menu) {
                        menu.applyCommand(payload.action(), payload.ticks(), payload.limit());
                    }
                }));
        registrar.playToClient(OGEHyperhopperSyncPayload.TYPE, OGEHyperhopperSyncPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> OGEHyperhopperScreen.handleSync(payload)));
        // ---- 金源超级漏斗：Ctrl（+Alt/Shift）点击旋转输入端/输出端朝向 ----
        registrar.playToServer(OGEHyperhopperRotatePayload.TYPE, OGEHyperhopperRotatePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        OGEHyperhopperBlock.rotateEnd(player, payload.pos(), payload.target(),
                                payload.roll(), payload.ccw());
                    }
                }));
        // ---- 金源超级熔炉：Ctrl+左键降档、Ctrl+右键升档（空手，1x/5x/20x） ----
        registrar.playToServer(OGEHyperfurnaceTierPayload.TYPE, OGEHyperfurnaceTierPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        OGEHyperfurnaceBlock.adjustTier(player, payload.pos(), payload.delta());
                    }
                }));
        // ---- 金源炼金站：把炼金凝造价量（CRV）同步给打开界面的玩家 ----
        registrar.playToClient(OGEChrysopoeiaSyncPayload.TYPE, OGEChrysopoeiaSyncPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> OGEChrysopoeiaStationScreen.handleSync(payload)));
        // ---- 金源自动合成机：界面里给合成格锁定/清除物品 ----
        registrar.playToServer(OGEMechCrafterLockPayload.TYPE, OGEMechCrafterLockPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player
                            && player.containerMenu instanceof OGEMechCrafterMenu menu) {
                        menu.setLock(payload.cell(), payload.stack());
                    }
                }));
        // ---- 虹彩印记：手持印记对空气 Ctrl+左/右键切档（5x/20x/100x） ----
        registrar.playToServer(OGERainbowMarkTierPayload.TYPE, OGERainbowMarkTierPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        OGERainbowMarkItem.adjustTier(player, payload.delta());
                    }
                }));
        // ---- 金源圆锯刃槽：界面“取出”按钮（报文不带坐标，只认当前打开的刃槽菜单） ----
        registrar.playToServer(OGESawbladeTakeXpPayload.TYPE, OGESawbladeTakeXpPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player
                            && player.containerMenu instanceof OGESawbladeMountMenu menu) {
                        menu.applyTakeXp(payload.amount());
                    }
                }));
        // ---- 金源圆锯刃槽：把存储经验同步给打开界面的玩家 ----
        registrar.playToClient(OGESawbladeMountSyncPayload.TYPE, OGESawbladeMountSyncPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> OGESawbladeMountScreen.handleSync(payload)));
        // ---- 挂机志愿者 ↔ 刃槽绑定：快捷绑定请求 / 切换绑定、改表、下发 ----
        registrar.playToServer(OGEVolunteerCtrlPayload.TYPE, OGEVolunteerCtrlPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        OGEVolunteerBindManager.beginQuickBind(player, payload.entityId());
                    }
                }));
        registrar.playToServer(OGEVolunteerBindTogglePayload.TYPE, OGEVolunteerBindTogglePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        OGEVolunteerBindManager.toggleBind(player, payload.entityId(), payload.pos());
                    }
                }));
        registrar.playToClient(OGEVolunteerQuickBindPayload.TYPE, OGEVolunteerQuickBindPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> OGEVolunteerBindClient.handleQuickBind(payload)));
        registrar.playToServer(OGEVolunteerBindEditPayload.TYPE, OGEVolunteerBindEditPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (!(context.player() instanceof ServerPlayer player)) {
                        return;
                    }
                    if (payload.action() == OGEVolunteerBindEditPayload.ACTION_RENAME) {
                        OGEVolunteerBindManager.rename(player, payload.entityId(),
                                payload.pos(), payload.name());
                    } else if (payload.action() == OGEVolunteerBindEditPayload.ACTION_DELETE) {
                        OGEVolunteerBindManager.delete(player, payload.entityId(), payload.pos());
                    } else {
                        java.util.List<net.minecraft.core.BlockPos> order = new java.util.ArrayList<>();
                        for (long packed : payload.positions()) {
                            order.add(net.minecraft.core.BlockPos.of(packed));
                        }
                        OGEVolunteerBindManager.reorder(player, payload.entityId(), order);
                    }
                }));
        registrar.playToClient(OGEVolunteerBindSyncPayload.TYPE, OGEVolunteerBindSyncPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> OGEVolunteerBindScreen.handleSync(payload)));
        // 刃槽显示名（唯一一份存在刃槽上）被改动：就地刷新所有开着的配置界面里对应那一行。
        registrar.playToClient(OGEVolunteerBindNamePayload.TYPE, OGEVolunteerBindNamePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> OGEVolunteerBindScreen.handleExternalRename(payload)));
    }
}
