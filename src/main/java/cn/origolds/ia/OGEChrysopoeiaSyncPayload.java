package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.Identifier;

public record OGEChrysopoeiaSyncPayload(int crv) implements CustomPacketPayload {
   public static final Type<OGEChrysopoeiaSyncPayload> TYPE = new Type(Identifier.fromNamespaceAndPath("origolds_ia", "oge_chrysopoeia_sync"));
   public static final StreamCodec<RegistryFriendlyByteBuf, OGEChrysopoeiaSyncPayload> STREAM_CODEC = new StreamCodec<RegistryFriendlyByteBuf, OGEChrysopoeiaSyncPayload>() {
      public OGEChrysopoeiaSyncPayload decode(RegistryFriendlyByteBuf buf) {
         return new OGEChrysopoeiaSyncPayload(buf.readVarInt());
      }

      public void encode(RegistryFriendlyByteBuf buf, OGEChrysopoeiaSyncPayload payload) {
         buf.writeVarInt(payload.crv());
      }
   };

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
