package cn.origolds.ia;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
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
   private static final int GLFW_KEY_LEFT_CONTROL = 341;
   private static final int GLFW_KEY_RIGHT_CONTROL = 345;
   private static final int GLFW_MOD_CONTROL = 2;

   private OGERainbowMarkClient() {
   }

   @SubscribeEvent
   public static void onMouseButton(Pre event) {
      if (event.getAction() == 1) {
         int button = event.getButton();
         if (button == 0 || button == 1) {
            if (isControl(event)) {
               if (isHoldingMarkAtAir(Minecraft.getInstance())) {
                  ClientPacketDistributor.sendToServer(new OGERainbowMarkTierPayload(button == 0 ? -1 : 1), new CustomPacketPayload[0]);
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
      if (mc.screen == null && mc.getOverlay() == null && mc.level != null) {
         LocalPlayer player = mc.player;
         return player != null && OGERainbowMarkItem.isUsable(player.getMainHandItem()) ? mc.hitResult == null || mc.hitResult.getType() == Type.MISS : false;
      } else {
         return false;
      }
   }

   private static boolean isControl(Pre event) {
      return (event.getModifiers() & 2) != 0 ? true : isControlHeld();
   }

   private static boolean isControlHeld() {
      Window window = Minecraft.getInstance().getWindow();
      return InputConstants.isKeyDown(window, 341) || InputConstants.isKeyDown(window, 345);
   }
}
