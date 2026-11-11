package cn.origolds.ia;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.HitResult.Type;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered;
import net.neoforged.neoforge.client.event.InputEvent.MouseButton.Pre;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, value = Dist.CLIENT)
public final class OGERainbowMarkClient {
   private OGERainbowMarkClient() {
   }

   @SubscribeEvent
   public static void onMouseButton(Pre event) {
      if (event.getAction() == 1) {
         int button = event.getButton();
         if (button == InputConstants.MOUSE_BUTTON_LEFT || button == InputConstants.MOUSE_BUTTON_RIGHT) {
            if (isControl(event)) {
               if (isHoldingMarkAtAir(Minecraft.getInstance())) {
                  ClientPacketDistributor.sendToServer(new OGERainbowMarkTierPayload(button == InputConstants.MOUSE_BUTTON_LEFT ? -1 : 1), new CustomPacketPayload[0]);
                  event.setCanceled(true);
               }
            }
         }
      }
   }

   @SubscribeEvent
   public static void onClickInput(InteractionKeyMappingTriggered event) {
      if (event.isAttack() || event.isUseItem()) {
         if (isControlHeld()) {
            if (isHoldingMarkAtAir(Minecraft.getInstance())) {
               event.setCanceled(true);
               event.setSwingHand(false);
            }
         }
      }
   }

   private static boolean isHoldingMarkAtAir(Minecraft mc) {
      // 26.3 起屏幕与覆盖层都不再挂在 Minecraft 上，改由 mc.gui 持有（原 mc.screen / mc.getOverlay()）
      if (mc.gui.screen() == null && mc.gui.overlay() == null && mc.level != null) {
         LocalPlayer player = mc.player;
         return player != null && OGERainbowMarkItem.isUsable(player.getMainHandItem()) ? mc.hitResult == null || mc.hitResult.getType() == Type.MISS : false;
      } else {
         return false;
      }
   }

   /** 是否按住 Ctrl（SDL 修饰位 + 物理键兜底）。 */
   private static boolean isControl(Pre event) {
      return (event.getModifiers() & InputConstants.MOD_CONTROL) != 0 ? true : isControlHeld();
   }

   /** 物理键位上的 Ctrl 是否按下。 */
   private static boolean isControlHeld() {
      return Minecraft.getInstance().hasControlDown();
   }
}
