package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务器：Ctrl+左键挂机志愿者，请求进入"快捷绑定"模式。
 *
 * <p>Ctrl 不是原版交互包携带的修饰键，服务端收不到，所以判定在
 * {@link OGEVolunteerBindClient}（Ctrl 按下 + 准星指到志愿者），这里只送实体 id。</p>
 */
public record OGEVolunteerCtrlPayload(int entityId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OGEVolunteerCtrlPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    OriGoldsIndustrializedAge.MOD_ID, "oge_volunteer_ctrl"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OGEVolunteerCtrlPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OGEVolunteerCtrlPayload decode(RegistryFriendlyByteBuf buf) {
                    return new OGEVolunteerCtrlPayload(buf.readVarInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OGEVolunteerCtrlPayload payload) {
                    buf.writeVarInt(payload.entityId());
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
