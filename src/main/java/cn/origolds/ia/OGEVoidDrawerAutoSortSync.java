package cn.origolds.ia;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 虚空抽屉的按键检测（客户端侧）：每 tick 判断 Shift / Ctrl / Alt 是否被按住，
 * 状态变化（或按住期间的心跳）时把 {@link OGEVoidDrawerPausePayload} 发给服务端。
 *
 * <p>服务端读不到键盘，所以"三个键是否被按下"这个判定只能在客户端做；服务端拿到的
 * 结果用于决定抽屉的排序循环是否跳过。按住期间每 {@value #KEEPALIVE_TICKS} tick 续报一次，
 * 配合服务端的超时自愈（见 {@link OGEVoidDrawerBlockEntity#setModifierHeld(boolean)}），
 * 松开包丢失也不会把整理永久卡停。关闭界面（screen 不再是抽屉）时自动补发一次
 * "未按住"。</p>
 *
 * <p>这样设计是为了与物品栏管理模组的"按住修饰键+左键划动快速转移"共存：
 * 按住期间一格搬空后不再补位，松手立即恢复紧凑。</p>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, value = Dist.CLIENT)
public final class OGEVoidDrawerAutoSortSync {

    /** 按住期间每隔多少 tick 续发一次"仍在按住"，供服务端延长有效期。 */
    private static final int KEEPALIVE_TICKS = 10;

    private static boolean lastSentHeld;
    private static int ticksSinceSent;

    private OGEVoidDrawerAutoSortSync() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            lastSentHeld = false;
            ticksSinceSent = 0;
            return;
        }
        // 26.3 起屏幕不再挂在 Minecraft 上，改由 mc.gui 持有（原 mc.screen）
        boolean held = mc.gui.screen() instanceof OGEVoidDrawerScreen && isModifierHeld(mc);
        if (!held && !lastSentHeld) {
            ticksSinceSent = 0;
            return;
        }
        if (held != lastSentHeld || ++ticksSinceSent >= KEEPALIVE_TICKS) {
            ClientPacketDistributor.sendToServer(new OGEVoidDrawerPausePayload(held));
            lastSentHeld = held;
            ticksSinceSent = 0;
        }
    }

    /** 左右 Shift / Ctrl / Alt 任一处于按下状态。 */
    private static boolean isModifierHeld(Minecraft mc) {
        return mc.hasShiftDown() || mc.hasControlDown() || mc.hasAltDown();
    }
}
