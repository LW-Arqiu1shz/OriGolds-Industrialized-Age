package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端→服务器：选择金源能量无线连接的一个端点。
 * <p>{@code start=true} 表示选择 A（Ctrl+左键），{@code start=false} 表示选择 B（Ctrl+右键）。</p>
 */
public record OGEWirelessSelectPayload(long pos, boolean start) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OGEWirelessSelectPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(OriGoldsIndustrializedAge.MOD_ID, "oge_wireless_select"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OGEWirelessSelectPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OGEWirelessSelectPayload decode(RegistryFriendlyByteBuf buf) {
                    return new OGEWirelessSelectPayload(buf.readLong(), buf.readBoolean());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OGEWirelessSelectPayload payload) {
                    buf.writeLong(payload.pos());
                    buf.writeBoolean(payload.start());
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
