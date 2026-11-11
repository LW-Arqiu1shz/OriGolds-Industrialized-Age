package cn.origolds.ia;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 虹彩印记的客户端交互：手持印记、准星对着空气，按住 Ctrl 再点鼠标切档
 * —— Ctrl+左键降档、Ctrl+右键升档。
 *
 * <p>为什么在客户端判定：Ctrl 不是原版交互包会携带的修饰键，服务端收不到
 * “是否按着 Ctrl”，所以客户端判定修饰键与准星后发
 * {@link OGERainbowMarkTierPayload}，服务端再校验主手物品与耐久
 * （见 {@link OGERainbowMarkItem#adjustTier}）。</p>
 *
 * <p>两个事件都要拦：{@code MouseButton.Pre} 拦住“按下瞬间”的挥空手，
 * {@code InteractionKeyMappingTriggered} 拦住“按住不放”时每刻重复的攻击 / 使用调用，
 * 否则 CTRL+左键会一边切档一边把准星下的方块挖掉。</p>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class OGERainbowMarkClient {

    // GLFW 键码与修饰位
    private static final int GLFW_KEY_LEFT_CONTROL = 341;
    private static final int GLFW_KEY_RIGHT_CONTROL = 345;
    private static final int GLFW_MOD_CONTROL = 2;

    private OGERainbowMarkClient() {
    }

    /** 按下瞬间（左键 = 0，右键 = 1）：发一次切档请求并吃掉这次点击。 */
    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (event.getAction() != 1) {
            return;
        }
        int button = event.getButton();
        if (button != 0 && button != 1) {
            return;
        }
        if (!isControl(event)) {
            return;
        }
        if (!isHoldingMarkAtAir(Minecraft.getInstance())) {
            return;
        }
        PacketDistributor.sendToServer(new OGERainbowMarkTierPayload(button == 0 ? -1 : 1));
        event.setCanceled(true);
    }

    /** 按住不放时每刻重复的攻击 / 使用调用：同样吃掉，避免顺手挖掉准星下的方块。 */
    @SubscribeEvent
    public static void onClickInput(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack() && !event.isUseItem()) {
            return;
        }
        if (!isControlHeld()) {
            return;
        }
        if (!isHoldingMarkAtAir(Minecraft.getInstance())) {
            return;
        }
        event.setCanceled(true);
        event.setSwingHand(false);
    }

    /** 无界面、主手是还能用的虹彩印记、准星指着空气（MISS）时才认这次操作。 */
    private static boolean isHoldingMarkAtAir(Minecraft mc) {
        if (mc.screen != null || mc.getOverlay() != null || mc.level == null) {
            return false;
        }
        LocalPlayer player = mc.player;
        if (player == null || !OGERainbowMarkItem.isUsable(player.getMainHandItem())) {
            return false;
        }
        // 对着方块或实体时不抢：那是正常的攻击 / 交互，不是“对着空气切档”。
        return mc.hitResult == null || mc.hitResult.getType() == HitResult.Type.MISS;
    }

    /** 是否按住 Ctrl（GLFW 修饰位 + 物理键兜底）。 */
    private static boolean isControl(InputEvent.MouseButton.Pre event) {
        if ((event.getModifiers() & GLFW_MOD_CONTROL) != 0) {
            return true;
        }
        return isControlHeld();
    }

    private static boolean isControlHeld() {
        long window = Minecraft.getInstance().getWindow().getWindow();
        return InputConstants.isKeyDown(window, GLFW_KEY_LEFT_CONTROL)
                || InputConstants.isKeyDown(window, GLFW_KEY_RIGHT_CONTROL);
    }
}
