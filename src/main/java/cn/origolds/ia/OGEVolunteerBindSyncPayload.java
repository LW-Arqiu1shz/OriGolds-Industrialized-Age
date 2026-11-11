package cn.origolds.ia;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.Identifier;

public record OGEVolunteerBindSyncPayload(int entityId, List<Long> positions, List<String> names) implements CustomPacketPayload {
   public static final Type<OGEVolunteerBindSyncPayload> TYPE = new Type(Identifier.fromNamespaceAndPath("origolds_ia", "oge_volunteer_bind_sync"));
   public static final StreamCodec<RegistryFriendlyByteBuf, OGEVolunteerBindSyncPayload> STREAM_CODEC = new StreamCodec<RegistryFriendlyByteBuf, OGEVolunteerBindSyncPayload>() {
      public OGEVolunteerBindSyncPayload decode(RegistryFriendlyByteBuf buf) {
         int entityId = buf.readVarInt();
         int count = buf.readVarInt();
         List<Long> positions = new ArrayList<>(count);
         List<String> names = new ArrayList<>(count);

         for (int i = 0; i < count; i++) {
            positions.add(buf.readLong());
            names.add(buf.readUtf(128));
         }

         return new OGEVolunteerBindSyncPayload(entityId, positions, names);
      }

      public void encode(RegistryFriendlyByteBuf buf, OGEVolunteerBindSyncPayload payload) {
         buf.writeVarInt(payload.entityId());
         buf.writeVarInt(payload.positions().size());

         for (int i = 0; i < payload.positions().size(); i++) {
            buf.writeLong(payload.positions().get(i));
            buf.writeUtf(payload.names().get(i), 128);
         }
      }
   };

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
