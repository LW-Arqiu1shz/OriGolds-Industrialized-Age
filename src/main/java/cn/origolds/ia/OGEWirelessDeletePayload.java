package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * 客户端→服务器：删除一条金源能量无线连线。
 */
public record OGEWirelessDeletePayload(UUID linkId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OGEWirelessDeletePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(OriGoldsIndustrializedAge.MOD_ID, "oge_wireless_delete"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OGEWirelessDeletePayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OGEWirelessDeletePayload decode(RegistryFriendlyByteBuf buf) {
                    return new OGEWirelessDeletePayload(buf.readUUID());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OGEWirelessDeletePayload payload) {
                    buf.writeUUID(payload.linkId());
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
