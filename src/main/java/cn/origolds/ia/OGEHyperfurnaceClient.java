package cn.origolds.ia;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * 金源超级熔炉的客户端交互：空手按住 Ctrl 再点鼠标切换熔炼档位
 * —— Ctrl+左键降档、Ctrl+右键升档。
 *
 * <p>为什么要在客户端判定：Ctrl 不是原版交互包会携带的修饰键，服务端收不到
 * “是否按着 Ctrl”，所以客户端判定 Ctrl 与准星指向后发
 * {@link OGEHyperfurnaceTierPayload}，服务端再校验空手、方块类型与距离
 * （见 {@link OGEHyperfurnaceBlock#adjustTier}）。</p>
 *
 * <p>两个事件都要拦：{@code MouseButton.Pre} 拦住“按下瞬间”的攻击/使用，避免顺手
 * 挖方块或挥空手；{@code InteractionKeyMappingTriggered} 拦住“按住不放”时每刻重复的
 * 攻击/使用调用，否则一直按着 Ctrl+左键会把超级熔炉慢慢挖掉。</p>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, value = Dist.CLIENT)
public final class OGEHyperfurnaceClient {

    private OGEHyperfurnaceClient() {
    }

    /** 按下瞬间（SDL 按钮号：左键 = 1，右键 = 3）：发一次改档请求并吃掉这次点击。 */
    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (event.getAction() != 1) {
            return;
        }
        int button = event.getButton();
        if (button != InputConstants.MOUSE_BUTTON_LEFT && button != InputConstants.MOUSE_BUTTON_RIGHT) {
            return;
        }
        if (!isControl(event)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.hitResult instanceof BlockHitResult hit) || !isAimingAtFurnace(mc)) {
            return;
        }
        // 左键 = -1（降档），右键 = +1（升档）。
        ClientPacketDistributor.sendToServer(new OGEHyperfurnaceTierPayload(
                hit.getBlockPos().asLong(), button == InputConstants.MOUSE_BUTTON_LEFT ? -1 : 1));
        event.setCanceled(true);
    }

    /** 按住不放时每刻重复的攻击/使用调用：同样吃掉，避免挖掉方块或触发原版交互。 */
    @SubscribeEvent
    public static void onClickInput(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack() && !event.isUseItem()) {
            return;
        }
        if (!Minecraft.getInstance().hasControlDown()) {
            return;
        }
        if (!isAimingAtFurnace(Minecraft.getInstance())) {
            return;
        }
        event.setCanceled(true);
        event.setSwingHand(false);
    }

    /** 空手、无界面、准星指着金源超级熔炉时才认这次操作。 */
    private static boolean isAimingAtFurnace(Minecraft mc) {
        // 26.3 起屏幕与覆盖层都不再挂在 Minecraft 上，改由 mc.gui 持有（原 mc.screen / mc.getOverlay()）
        if (mc.gui.screen() != null || mc.gui.overlay() != null || mc.level == null) {
            return false;
        }
        LocalPlayer player = mc.player;
        if (player == null || !player.getMainHandItem().isEmpty()) {
            return false;
        }
        if (!(mc.hitResult instanceof BlockHitResult hit)) {
            return false;
        }
        return mc.level.getBlockState(hit.getBlockPos()).getBlock() == ModBlocks.ORIGOLD_HYPERFURNACE.get();
    }

    /** 是否按住 Ctrl（SDL 修饰位 + 物理键兜底）。 */
    private static boolean isControl(InputEvent.MouseButton.Pre event) {
        if ((event.getModifiers() & InputConstants.MOD_CONTROL) != 0) {
            return true;
        }
        return Minecraft.getInstance().hasControlDown();
    }
}
