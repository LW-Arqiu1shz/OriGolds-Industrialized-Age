package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.Identifier;

public record OGEHyperfurnaceTierPayload(long pos, int delta) implements CustomPacketPayload {
   public static final Type<OGEHyperfurnaceTierPayload> TYPE = new Type(Identifier.fromNamespaceAndPath("origolds_ia", "oge_hyperfurnace_tier"));
   public static final StreamCodec<RegistryFriendlyByteBuf, OGEHyperfurnaceTierPayload> STREAM_CODEC = new StreamCodec<RegistryFriendlyByteBuf, OGEHyperfurnaceTierPayload>() {
      public OGEHyperfurnaceTierPayload decode(RegistryFriendlyByteBuf buf) {
         return new OGEHyperfurnaceTierPayload(buf.readLong(), buf.readVarInt());
      }

      public void encode(RegistryFriendlyByteBuf buf, OGEHyperfurnaceTierPayload payload) {
         buf.writeLong(payload.pos());
         buf.writeVarInt(payload.delta());
      }
   };

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
