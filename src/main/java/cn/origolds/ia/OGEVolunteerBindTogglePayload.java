package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务器：快捷绑定模式里左键点中白色线框，切换该刃槽与志愿者的绑定状态。
 * 服务端复核实体、刃槽与 32 格距离，见 {@link OGEVolunteerBindManager#toggleBind}。
 */
public record OGEVolunteerBindTogglePayload(int entityId, long pos) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OGEVolunteerBindTogglePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    OriGoldsIndustrializedAge.MOD_ID, "oge_volunteer_bind_toggle"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OGEVolunteerBindTogglePayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OGEVolunteerBindTogglePayload decode(RegistryFriendlyByteBuf buf) {
                    return new OGEVolunteerBindTogglePayload(buf.readVarInt(), buf.readLong());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OGEVolunteerBindTogglePayload payload) {
                    buf.writeVarInt(payload.entityId());
                    buf.writeLong(payload.pos());
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
