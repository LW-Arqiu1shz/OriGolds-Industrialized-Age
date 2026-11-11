package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.Identifier;

public record OGEVolunteerBindTogglePayload(int entityId, long pos) implements CustomPacketPayload {
   public static final Type<OGEVolunteerBindTogglePayload> TYPE = new Type(Identifier.fromNamespaceAndPath("origolds_ia", "oge_volunteer_bind_toggle"));
   public static final StreamCodec<RegistryFriendlyByteBuf, OGEVolunteerBindTogglePayload> STREAM_CODEC = new StreamCodec<RegistryFriendlyByteBuf, OGEVolunteerBindTogglePayload>() {
      public OGEVolunteerBindTogglePayload decode(RegistryFriendlyByteBuf buf) {
         return new OGEVolunteerBindTogglePayload(buf.readVarInt(), buf.readLong());
      }

      public void encode(RegistryFriendlyByteBuf buf, OGEVolunteerBindTogglePayload payload) {
         buf.writeVarInt(payload.entityId());
         buf.writeLong(payload.pos());
      }
   };

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
