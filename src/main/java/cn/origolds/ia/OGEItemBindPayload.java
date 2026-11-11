package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.Identifier;

public record OGEItemBindPayload(long pos) implements CustomPacketPayload {
   public static final Type<OGEItemBindPayload> TYPE = new Type(Identifier.fromNamespaceAndPath("origolds_ia", "oge_item_bind"));
   public static final StreamCodec<RegistryFriendlyByteBuf, OGEItemBindPayload> STREAM_CODEC = new StreamCodec<RegistryFriendlyByteBuf, OGEItemBindPayload>() {
      public OGEItemBindPayload decode(RegistryFriendlyByteBuf buf) {
         return new OGEItemBindPayload(buf.readLong());
      }

      public void encode(RegistryFriendlyByteBuf buf, OGEItemBindPayload payload) {
         buf.writeLong(payload.pos());
      }
   };

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
