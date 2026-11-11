package cn.origolds.ia;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.Identifier;

public record OGEVolunteerBindEditPayload(int entityId, int action, long pos, String name, List<Long> positions) implements CustomPacketPayload {
   public static final int ACTION_RENAME = 0;
   public static final int ACTION_REORDER = 1;
   public static final int ACTION_DELETE = 2;
   public static final Type<OGEVolunteerBindEditPayload> TYPE = new Type(Identifier.fromNamespaceAndPath("origolds_ia", "oge_volunteer_bind_edit"));
   public static final StreamCodec<RegistryFriendlyByteBuf, OGEVolunteerBindEditPayload> STREAM_CODEC = new StreamCodec<RegistryFriendlyByteBuf, OGEVolunteerBindEditPayload>() {
      public OGEVolunteerBindEditPayload decode(RegistryFriendlyByteBuf buf) {
         int entityId = buf.readVarInt();
         int action = buf.readVarInt();
         if (action == 0) {
            return OGEVolunteerBindEditPayload.ofRename(entityId, buf.readLong(), buf.readUtf(128));
         } else if (action == 2) {
            return OGEVolunteerBindEditPayload.ofDelete(entityId, buf.readLong());
         } else {
            int count = buf.readVarInt();
            List<Long> positions = new ArrayList<>(count);

            for (int i = 0; i < count; i++) {
               positions.add(buf.readLong());
            }

            return OGEVolunteerBindEditPayload.ofReorder(entityId, positions);
         }
      }

      public void encode(RegistryFriendlyByteBuf buf, OGEVolunteerBindEditPayload payload) {
         buf.writeVarInt(payload.entityId());
         buf.writeVarInt(payload.action());
         if (payload.action() == 0) {
            buf.writeLong(payload.pos());
            buf.writeUtf(payload.name(), 128);
         } else if (payload.action() == 2) {
            buf.writeLong(payload.pos());
         } else {
            buf.writeVarInt(payload.positions().size());

            for (long pos : payload.positions()) {
               buf.writeLong(pos);
            }
         }
      }
   };

   public static OGEVolunteerBindEditPayload ofRename(int entityId, long pos, String name) {
      return new OGEVolunteerBindEditPayload(entityId, 0, pos, name, List.of());
   }

   public static OGEVolunteerBindEditPayload ofReorder(int entityId, List<Long> positions) {
      return new OGEVolunteerBindEditPayload(entityId, 1, 0L, "", positions);
   }

   public static OGEVolunteerBindEditPayload ofDelete(int entityId, long pos) {
      return new OGEVolunteerBindEditPayload(entityId, 2, pos, "", List.of());
   }

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
