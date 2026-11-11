package cn.origolds.ia;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 金源引力匣的客户端交互：空手按住 Alt 再点鼠标切换吸附档位
 * —— Alt+左键降档（范围缩小）、Alt+右键升档（范围扩大），左下右上。
 *
 * <p>为什么要在客户端判定：Alt 不是原版交互包会携带的修饰键，服务端收不到“是否按着 Alt”，
 * 所以客户端判定 Alt 与准星指向后发 {@link OGEGravityCassetteTierPayload}，服务端再校验
 * 空手、方块类型与距离（见 {@link OGEGravityCassetteBlock#adjustTier}）。</p>
 *
 * <p>两个事件都要拦：{@code MouseButton.Pre} 拦住“按下瞬间”的攻击/使用，避免顺手挥空手
 * 或触发方块自身交互；{@code InteractionKeyMappingTriggered} 拦住“按住不放”时每刻重复的
 * 攻击/使用调用（{@code startAttack} / {@code continueAttack} / {@code startUseItem}），
 * 否则一直按着 Alt+左键会把引力匣慢慢挖掉。</p>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class OGEGravityCassetteClient {

    // GLFW 键码
    private static final int GLFW_KEY_LEFT_ALT = 342;
    private static final int GLFW_KEY_RIGHT_ALT = 346;

    private OGEGravityCassetteClient() {
    }

    /** 按下瞬间（左键 = 0，右键 = 1）：发一次改档请求并吃掉这次点击。 */
    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (event.getAction() != 1) {
            return;
        }
        int button = event.getButton();
        if (button != 0 && button != 1) {
            return;
        }
        if (!isAlt(event)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!isAimingAtCassette(mc)) {
            return;
        }
        // 左键 = -1（降档），右键 = +1（升档）：左下右上。
        PacketDistributor.sendToServer(new OGEGravityCassetteTierPayload(
                ((BlockHitResult) mc.hitResult).getBlockPos().asLong(), button == 0 ? -1 : 1));
        event.setCanceled(true);
    }

    /** 按住不放时每刻重复的攻击/使用调用：同样吃掉，避免挖掉方块或触发原版交互。 */
    @SubscribeEvent
    public static void onClickInput(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack() && !event.isUseItem()) {
            return;
        }
        if (!isAltHeld()) {
            return;
        }
        if (!isAimingAtCassette(Minecraft.getInstance())) {
            return;
        }
        event.setCanceled(true);
        event.setSwingHand(false);
    }

    /** 空手、无界面、准星指着金源引力匣时才认这次操作。 */
    private static boolean isAimingAtCassette(Minecraft mc) {
        if (mc.screen != null || mc.getOverlay() != null || mc.level == null) {
            return false;
        }
        LocalPlayer player = mc.player;
        if (player == null || !player.getMainHandItem().isEmpty()) {
            return false;
        }
        if (!(mc.hitResult instanceof BlockHitResult hit)) {
            return false;
        }
        return mc.level.getBlockState(hit.getBlockPos()).getBlock() == ModBlocks.ORIGOLD_GRAVITY_CASSETTE.get();
    }

    /** 是否按住 Alt（GLFW 修饰位 + 物理键兜底）。 */
    private static boolean isAlt(InputEvent.MouseButton.Pre event) {
        if ((event.getModifiers() & 4) != 0) {
            return true;
        }
        return isAltHeld();
    }

    /** 物理键位上的 Alt 是否按下。 */
    private static boolean isAltHeld() {
        long window = Minecraft.getInstance().getWindow().getWindow();
        return InputConstants.isKeyDown(window, GLFW_KEY_LEFT_ALT)
                || InputConstants.isKeyDown(window, GLFW_KEY_RIGHT_ALT);
    }
}
