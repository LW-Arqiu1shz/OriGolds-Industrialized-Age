package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务器：选择无线物流管道的一个端点。
 * <p>{@code start=true} 表示选择起点（Alt+左键），{@code start=false} 表示选择终点（Alt+右键）。</p>
 */
public record OGEPipeSelectPayload(long pos, boolean start) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OGEPipeSelectPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(OriGoldsIndustrializedAge.MOD_ID, "oge_pipe_select"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OGEPipeSelectPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OGEPipeSelectPayload decode(RegistryFriendlyByteBuf buf) {
                    return new OGEPipeSelectPayload(buf.readLong(), buf.readBoolean());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OGEPipeSelectPayload payload) {
                    buf.writeLong(payload.pos());
                    buf.writeBoolean(payload.start());
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
