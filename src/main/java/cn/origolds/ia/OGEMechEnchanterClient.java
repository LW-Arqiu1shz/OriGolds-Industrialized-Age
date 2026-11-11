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
 * 金源自动附魔机的客户端交互：空手按住 Ctrl 再点鼠标切换档位
 * —— Ctrl+左键降档、Ctrl+右键升档。
 *
 * <p>为什么要在客户端判定：Ctrl 不是原版交互包会携带的修饰键，服务端收不到
 * “是否按着 Ctrl”，所以客户端判定修饰键与准星指向后发
 * {@link OGEMechEnchanterTierPayload}，服务端再校验空手、方块类型与距离
 * （见 {@link OGEMechEnchanterBlock#adjustTier}）。</p>
 *
 * <p>两个事件都要拦：{@code MouseButton.Pre} 拦住“按下瞬间”的攻击/使用，避免顺手
 * 挥空手或触发方块自身交互；{@code InteractionKeyMappingTriggered} 拦住“按住不放”
 * 时每刻重复的攻击/使用调用，否则一直按着 Ctrl+左键会把附魔机慢慢挖掉。</p>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class OGEMechEnchanterClient {

    // GLFW 键码与修饰位
    private static final int GLFW_KEY_LEFT_CONTROL = 341;
    private static final int GLFW_KEY_RIGHT_CONTROL = 345;
    private static final int GLFW_MOD_CONTROL = 2;

    private OGEMechEnchanterClient() {
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
        if (!isControl(event)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!isAimingAtEnchanter(mc)) {
            return;
        }
        PacketDistributor.sendToServer(new OGEMechEnchanterTierPayload(
                ((BlockHitResult) mc.hitResult).getBlockPos().asLong(), button == 0 ? -1 : 1));
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
        if (!isAimingAtEnchanter(Minecraft.getInstance())) {
            return;
        }
        event.setCanceled(true);
        event.setSwingHand(false);
    }

    /** 空手、无界面、准星指着金源自动附魔机时才认这次操作。 */
    private static boolean isAimingAtEnchanter(Minecraft mc) {
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
        return mc.level.getBlockState(hit.getBlockPos()).getBlock() == ModBlocks.ORIGOLD_MECH_ENCHANTER.get();
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
