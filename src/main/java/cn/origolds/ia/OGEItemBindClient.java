package cn.origolds.ia;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * 金源虚空抽屉 / 金源引力匣 / 金源超级漏斗的客户端绑定交互：手持物品 Alt+中键绑定该物品、
 * 空手 Alt+中键清空绑定。
 *
 * <p>Alt 不是原版交互包会携带的修饰键，服务端读不到键盘，所以这里判定 Alt 与准星指向，
 * 再把 {@link OGEItemBindPayload} 发给服务端复核（见 {@link OGEItemBind}）。中键默认是
 * “选取方块”，事件被取消后不会再触发原版的中键行为。可绑定的方块统一实现
 * {@link OGEItemBindableBlock}，这里按标记接口判断，不再逐个列举机器；距离上限也与
 * 服务端共用 {@link OGEItemBind#MAX_INTERACT_DISTANCE_SQ}，服务端还会把这两件事重新校验一遍。</p>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class OGEItemBindClient {

    private OGEItemBindClient() {
    }

    /** 按下瞬间（中键）：瞄准可绑定机器且按住 Alt 时发一次绑定请求并吃掉这次点击。 */
    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (event.getAction() != 1 || event.getButton() != GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            return;
        }
        if (!isAlt(event)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.getOverlay() != null || mc.level == null || mc.player == null) {
            return;
        }
        if (!(mc.hitResult instanceof BlockHitResult hit)) {
            return;
        }
        Block block = mc.level.getBlockState(hit.getBlockPos()).getBlock();
        if (!(block instanceof OGEItemBindableBlock)) {
            return;
        }
        LocalPlayer player = mc.player;
        if (player.getEyePosition().distanceToSqr(Vec3.atCenterOf(hit.getBlockPos()))
                > OGEItemBind.MAX_INTERACT_DISTANCE_SQ) {
            return;
        }
        PacketDistributor.sendToServer(new OGEItemBindPayload(hit.getBlockPos().asLong()));
        event.setCanceled(true);
    }

    /** 是否按住 Alt（GLFW 修饰位 + 物理键兜底）。 */
    private static boolean isAlt(InputEvent.MouseButton.Pre event) {
        if ((event.getModifiers() & GLFW.GLFW_MOD_ALT) != 0) {
            return true;
        }
        return isAltHeld();
    }

    /** 物理键位上的 Alt 是否按下。 */
    private static boolean isAltHeld() {
        long window = Minecraft.getInstance().getWindow().getWindow();
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT)
                || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT);
    }
}
