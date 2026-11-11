package cn.origolds.ia;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent.MouseButton.Pre;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID, value = Dist.CLIENT)
public final class OGEItemBindClient {
   private OGEItemBindClient() {
   }

   @SubscribeEvent
   public static void onMouseButton(Pre event) {
      if (event.getAction() == 1 && event.getButton() == 2) {
         if (isAlt(event)) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen == null && mc.getOverlay() == null && mc.level != null && mc.player != null) {
               if (mc.hitResult instanceof BlockHitResult hit) {
                  Block block = mc.level.getBlockState(hit.getBlockPos()).getBlock();
                  if (block instanceof OGEItemBindableBlock) {
                     LocalPlayer player = mc.player;
                     if (!(player.getEyePosition().distanceToSqr(Vec3.atCenterOf(hit.getBlockPos())) > 64.0)) {
                        ClientPacketDistributor.sendToServer(new OGEItemBindPayload(hit.getBlockPos().asLong()), new CustomPacketPayload[0]);
                        event.setCanceled(true);
                     }
                  }
               }
            }
         }
      }
   }

   private static boolean isAlt(Pre event) {
      return (event.getModifiers() & 4) != 0 ? true : isAltHeld();
   }

   private static boolean isAltHeld() {
      Window window = Minecraft.getInstance().getWindow();
      return InputConstants.isKeyDown(window, 342) || InputConstants.isKeyDown(window, 346);
   }
}
