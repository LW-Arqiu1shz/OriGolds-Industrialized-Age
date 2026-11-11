package cn.origolds.ia;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务器→客户端：快捷绑定模式所需的绑定表——目标志愿者实体 id + 它已绑定的刃槽坐标。
 *
 * <p>客户端据此在 32 格半径内把刃槽画成白色线框并区分已绑定 / 未绑定；
 * 进入模式与每次切换绑定后各下发一次，客户端状态以服务端为准。</p>
 */
public record OGEVolunteerQuickBindPayload(int entityId, List<Long> positions) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OGEVolunteerQuickBindPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    OriGoldsIndustrializedAge.MOD_ID, "oge_volunteer_quick_bind"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OGEVolunteerQuickBindPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OGEVolunteerQuickBindPayload decode(RegistryFriendlyByteBuf buf) {
                    int entityId = buf.readVarInt();
                    int count = buf.readVarInt();
                    List<Long> positions = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        positions.add(buf.readLong());
                    }
                    return new OGEVolunteerQuickBindPayload(entityId, positions);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OGEVolunteerQuickBindPayload payload) {
                    buf.writeVarInt(payload.entityId());
                    buf.writeVarInt(payload.positions().size());
                    for (long pos : payload.positions()) {
                        buf.writeLong(pos);
                    }
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
