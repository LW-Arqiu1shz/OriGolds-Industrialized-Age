package cn.origolds.ia;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public interface OGEItemBindable {
   ItemStack getBound();

   void setBound(ItemStack var1);

   default boolean isBindingCompatible(ItemStack stack) {
      return true;
   }

   default Component bindRejectedMessage() {
      return Component.translatable("message.origolds_ia.drawer_bind_not_empty");
   }
}
