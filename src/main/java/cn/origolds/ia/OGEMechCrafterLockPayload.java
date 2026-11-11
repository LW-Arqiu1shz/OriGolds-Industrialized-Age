package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

public record OGEMechCrafterLockPayload(int cell, ItemStack stack) implements CustomPacketPayload {
   public static final Type<OGEMechCrafterLockPayload> TYPE = new Type(Identifier.fromNamespaceAndPath("origolds_ia", "oge_mech_crafter_lock"));
   public static final StreamCodec<RegistryFriendlyByteBuf, OGEMechCrafterLockPayload> STREAM_CODEC = new StreamCodec<RegistryFriendlyByteBuf, OGEMechCrafterLockPayload>() {
      public OGEMechCrafterLockPayload decode(RegistryFriendlyByteBuf buf) {
         int cell = buf.readVarInt();
         ItemStack stack = (ItemStack)ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
         return new OGEMechCrafterLockPayload(cell, stack);
      }

      public void encode(RegistryFriendlyByteBuf buf, OGEMechCrafterLockPayload payload) {
         buf.writeVarInt(payload.cell());
         ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, payload.stack());
      }
   };

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
