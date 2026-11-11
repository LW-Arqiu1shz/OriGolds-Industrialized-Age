package cn.origolds.ia;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.Identifier;

public record OGEVolunteerQuickBindPayload(int entityId, List<Long> positions) implements CustomPacketPayload {
   public static final Type<OGEVolunteerQuickBindPayload> TYPE = new Type(Identifier.fromNamespaceAndPath("origolds_ia", "oge_volunteer_quick_bind"));
   public static final StreamCodec<RegistryFriendlyByteBuf, OGEVolunteerQuickBindPayload> STREAM_CODEC = new StreamCodec<RegistryFriendlyByteBuf, OGEVolunteerQuickBindPayload>() {
      public OGEVolunteerQuickBindPayload decode(RegistryFriendlyByteBuf buf) {
         int entityId = buf.readVarInt();
         int count = buf.readVarInt();
         List<Long> positions = new ArrayList<>(count);

         for (int i = 0; i < count; i++) {
            positions.add(buf.readLong());
         }

         return new OGEVolunteerQuickBindPayload(entityId, positions);
      }

      public void encode(RegistryFriendlyByteBuf buf, OGEVolunteerQuickBindPayload payload) {
         buf.writeVarInt(payload.entityId());
         buf.writeVarInt(payload.positions().size());

         for (long pos : payload.positions()) {
            buf.writeLong(pos);
         }
      }
   };

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
