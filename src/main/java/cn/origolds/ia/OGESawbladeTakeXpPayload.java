package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务器：在金源圆锯刃槽界面里点“取出”，请求取出 {@code amount} 点经验。
 *
 * <p>报文不带坐标，服务端只认玩家当前打开的 {@link OGESawbladeMountMenu}，
 * 防止客户端借这个包操作任意位置的刃槽。</p>
 */
public record OGESawbladeTakeXpPayload(int amount) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OGESawbladeTakeXpPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    OriGoldsIndustrializedAge.MOD_ID, "oge_sawblade_take_xp"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OGESawbladeTakeXpPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OGESawbladeTakeXpPayload decode(RegistryFriendlyByteBuf buf) {
                    return new OGESawbladeTakeXpPayload(buf.readVarInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OGESawbladeTakeXpPayload payload) {
                    buf.writeVarInt(payload.amount());
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
