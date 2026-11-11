package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * 客户端→服务器：删除一条无线物流管道（物流显示模式下悬停后按 Delete）。
 */
public record OGEPipeDeletePayload(UUID pipeId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OGEPipeDeletePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(OriGoldsIndustrializedAge.MOD_ID, "oge_pipe_delete"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OGEPipeDeletePayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OGEPipeDeletePayload decode(RegistryFriendlyByteBuf buf) {
                    return new OGEPipeDeletePayload(buf.readUUID());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OGEPipeDeletePayload payload) {
                    buf.writeUUID(payload.pipeId());
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
