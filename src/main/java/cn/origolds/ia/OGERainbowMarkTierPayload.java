package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.Identifier;

public record OGERainbowMarkTierPayload(int delta) implements CustomPacketPayload {
   public static final Type<OGERainbowMarkTierPayload> TYPE = new Type(Identifier.fromNamespaceAndPath("origolds_ia", "oge_rainbow_mark_tier"));
   public static final StreamCodec<RegistryFriendlyByteBuf, OGERainbowMarkTierPayload> STREAM_CODEC = new StreamCodec<RegistryFriendlyByteBuf, OGERainbowMarkTierPayload>() {
      public OGERainbowMarkTierPayload decode(RegistryFriendlyByteBuf buf) {
         return new OGERainbowMarkTierPayload(buf.readVarInt());
      }

      public void encode(RegistryFriendlyByteBuf buf, OGERainbowMarkTierPayload payload) {
         buf.writeVarInt(payload.delta());
      }
   };

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
