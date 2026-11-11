package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务器→客户端：把金源炼金站当前累积的炼金凝造价量（CRV）同步给打开界面的玩家。
 *
 * <p>CRV 是每刻都在变的 {@code int}，量程超过菜单数据槽（线上按 short 传输），
 * 所以单开一个报文，由 {@link OGEChrysopoeiaStationMenu#broadcastChanges()} 在数值
 * 变化时下发，界面据此画进度条。</p>
 *
 * @param crv 当前炼金凝造价量
 */
public record OGEChrysopoeiaSyncPayload(int crv) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OGEChrysopoeiaSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    OriGoldsIndustrializedAge.MOD_ID, "oge_chrysopoeia_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OGEChrysopoeiaSyncPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OGEChrysopoeiaSyncPayload decode(RegistryFriendlyByteBuf buf) {
                    return new OGEChrysopoeiaSyncPayload(buf.readVarInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OGEChrysopoeiaSyncPayload payload) {
                    buf.writeVarInt(payload.crv());
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
