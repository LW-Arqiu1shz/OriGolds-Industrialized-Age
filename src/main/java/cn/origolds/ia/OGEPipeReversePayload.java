package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * 客户端→服务器：反转一条无线物流管道的方向（物流显示模式下悬停后按 Backspace）。
 */
public record OGEPipeReversePayload(UUID pipeId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OGEPipeReversePayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(OriGoldsIndustrializedAge.MOD_ID, "oge_pipe_reverse"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OGEPipeReversePayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OGEPipeReversePayload decode(RegistryFriendlyByteBuf buf) {
                    return new OGEPipeReversePayload(buf.readUUID());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OGEPipeReversePayload payload) {
                    buf.writeUUID(payload.pipeId());
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
