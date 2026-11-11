package cn.origolds.ia;

import com.mojang.blaze3d.platform.Window;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 金源超级漏斗的客户端交互：空手按住 Ctrl（可加 Alt / Shift）再点鼠标旋转输入端/输出端。
 *
 * <ul>
 *   <li>Ctrl + 左键 / 右键：输入端绕竖直轴逆时针 / 顺时针转 90°；</li>
 *   <li>Ctrl + Alt + 左键 / 右键：输入端翻转到另一个垂直面；</li>
 *   <li>Ctrl + Shift + 左键 / 右键：输出端绕竖直轴逆时针 / 顺时针转 90°；</li>
 *   <li>Ctrl + Shift + Alt + 左键 / 右键：输出端翻转到另一个垂直面。</li>
 * </ul>
 *
 * <p>Ctrl / Alt / Shift 这些修饰键原版交互包不会完整携带，所以判定在客户端完成，
 * 服务端收到 {@link OGEHyperhopperRotatePayload} 后由
 * {@link OGEHyperhopperBlock#rotateEnd} 重新校验空手、方块类型、距离与参考系。</p>
 *
 * <p>两个事件都要拦：{@code MouseButton.Pre} 拦住“按下瞬间”的攻击/使用，
 * {@code InteractionKeyMappingTriggered} 拦住“按住不放”时每刻重复的调用，
 * 否则一直按着 Ctrl+左键会把方块挖掉。</p>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, value = Dist.CLIENT)
public final class OGEHyperhopperClient {

    // GLFW 键码
    private static final int GLFW_KEY_LEFT_SHIFT = 340;
    private static final int GLFW_KEY_RIGHT_SHIFT = 344;
    private static final int GLFW_KEY_LEFT_CONTROL = 341;
    private static final int GLFW_KEY_RIGHT_CONTROL = 345;
    private static final int GLFW_KEY_LEFT_ALT = 342;
    private static final int GLFW_KEY_RIGHT_ALT = 346;

    // GLFW 修饰位
    private static final int GLFW_MOD_SHIFT = 1;
    private static final int GLFW_MOD_CONTROL = 2;
    private static final int GLFW_MOD_ALT = 4;

    private OGEHyperhopperClient() {
    }

    /** 按下瞬间（左键 = 0，右键 = 1）：发一次旋转请求并吃掉这次点击。 */
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
        Minecraft mc = Minecraft.getInstance();
        if (!isAimingAtHopper(mc)) {
            return;
        }
        boolean shift = isShift(event);
        boolean alt = isAlt(event);
        int target = shift ? OGEHyperhopperBlock.TARGET_OUTPUT : OGEHyperhopperBlock.TARGET_INPUT;
        boolean ccw = button == 0; // 左键逆时针、右键顺时针
        ClientPacketDistributor.sendToServer(new OGEHyperhopperRotatePayload(
                ((BlockHitResult) mc.hitResult).getBlockPos().asLong(), target, alt, ccw));
        event.setCanceled(true);
    }

    /** 按住不放时每刻重复的攻击/使用调用：同样吃掉，避免挖掉方块或触发原版交互。 */
    @SubscribeEvent
    public static void onClickInput(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack() && !event.isUseItem()) {
            return;
        }
        if (!isControlHeld()) {
            return;
        }
        if (!isAimingAtHopper(Minecraft.getInstance())) {
            return;
        }
        event.setCanceled(true);
        event.setSwingHand(false);
    }

    /** 空手、无界面、准星指着金源超级漏斗时才认这次操作。 */
    private static boolean isAimingAtHopper(Minecraft mc) {
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
        return mc.level.getBlockState(hit.getBlockPos()).getBlock() == ModBlocks.ORIGOLD_HYPERHOPPER.get();
    }

    /** 是否按住 Ctrl（GLFW 修饰位 + 物理键兜底）。 */
    private static boolean isControl(InputEvent.MouseButton.Pre event) {
        if ((event.getModifiers() & GLFW_MOD_CONTROL) != 0) {
            return true;
        }
        return isControlHeld();
    }

    /** 是否按住 Alt（GLFW 修饰位 + 物理键兜底）。 */
    private static boolean isAlt(InputEvent.MouseButton.Pre event) {
        if ((event.getModifiers() & GLFW_MOD_ALT) != 0) {
            return true;
        }
        return isAltHeld();
    }

    /** 是否按住 Shift（GLFW 修饰位 + 物理键兜底）。 */
    private static boolean isShift(InputEvent.MouseButton.Pre event) {
        if ((event.getModifiers() & GLFW_MOD_SHIFT) != 0) {
            return true;
        }
        return isShiftHeld();
    }

    private static boolean isControlHeld() {
        Window window = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(window, GLFW_KEY_LEFT_CONTROL)
                || InputConstants.isKeyDown(window, GLFW_KEY_RIGHT_CONTROL);
    }

    private static boolean isAltHeld() {
        Window window = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(window, GLFW_KEY_LEFT_ALT)
                || InputConstants.isKeyDown(window, GLFW_KEY_RIGHT_ALT);
    }

    private static boolean isShiftHeld() {
        Window window = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(window, GLFW_KEY_LEFT_SHIFT)
                || InputConstants.isKeyDown(window, GLFW_KEY_RIGHT_SHIFT);
    }
}
