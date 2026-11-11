package cn.origolds.ia;

import com.mojang.blaze3d.platform.InputConstants;
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
      if (event.getAction() == 1 && event.getButton() == InputConstants.MOUSE_BUTTON_MIDDLE) {
         if (isAlt(event)) {
            Minecraft mc = Minecraft.getInstance();
            // 26.3 起屏幕与覆盖层都不再挂在 Minecraft 上，改由 mc.gui 持有（原 mc.screen / mc.getOverlay()）
            if (mc.gui.screen() == null && mc.gui.overlay() == null && mc.level != null && mc.player != null) {
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
      return (event.getModifiers() & InputConstants.MOD_ALT) != 0 ? true : isAltHeld();
   }

   /** 是否按住 Alt（SDL 修饰位 + 物理键兜底）。 */
   private static boolean isAltHeld() {
      return Minecraft.getInstance().hasAltDown();
   }
}
