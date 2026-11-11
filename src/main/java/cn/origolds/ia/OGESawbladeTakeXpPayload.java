package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.Identifier;

/**
 * C2S：锯片台界面点"取出"，请求从储经验里取走 {@code amount} 点
 * （0 表示全部）。服务端处理器转交 {@link OGESawbladeMountMenu#applyTakeXp(int)}。
 */
public record OGESawbladeTakeXpPayload(int amount) implements CustomPacketPayload {

    public static final Type<OGESawbladeTakeXpPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(OriGoldsIndustrializedAge.MOD_ID, "oge_sawblade_take_xp"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OGESawbladeTakeXpPayload> STREAM_CODEC =
            new StreamCodec<RegistryFriendlyByteBuf, OGESawbladeTakeXpPayload>() {
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
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
