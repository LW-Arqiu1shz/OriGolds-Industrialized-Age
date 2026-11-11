package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.Identifier;

public record OGEVolunteerCtrlPayload(int entityId) implements CustomPacketPayload {
   public static final Type<OGEVolunteerCtrlPayload> TYPE = new Type(Identifier.fromNamespaceAndPath("origolds_ia", "oge_volunteer_ctrl"));
   public static final StreamCodec<RegistryFriendlyByteBuf, OGEVolunteerCtrlPayload> STREAM_CODEC = new StreamCodec<RegistryFriendlyByteBuf, OGEVolunteerCtrlPayload>() {
      public OGEVolunteerCtrlPayload decode(RegistryFriendlyByteBuf buf) {
         return new OGEVolunteerCtrlPayload(buf.readVarInt());
      }

      public void encode(RegistryFriendlyByteBuf buf, OGEVolunteerCtrlPayload payload) {
         buf.writeVarInt(payload.entityId());
      }
   };

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
