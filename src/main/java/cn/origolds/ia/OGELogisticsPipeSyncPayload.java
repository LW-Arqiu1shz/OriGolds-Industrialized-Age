package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 服务器→客户端：同步所有单向无线物流管道，用于客户端绘制
 * 起点青色、终点橙色、线体青→橙渐变的管道连线与端点框。
 */
public record OGELogisticsPipeSyncPayload(List<PipeView> pipes) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OGELogisticsPipeSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(OriGoldsIndustrializedAge.MOD_ID, "oge_pipe_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OGELogisticsPipeSyncPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OGELogisticsPipeSyncPayload decode(RegistryFriendlyByteBuf buf) {
                    int size = buf.readVarInt();
                    List<PipeView> pipes = new ArrayList<>(size);
                    for (int i = 0; i < size; ++i) {
                        UUID id = buf.readUUID();
                        ResourceKey<Level> dimFrom = OGEWirelessData.dimKey(buf.readIdentifier());
                        long posFrom = buf.readLong();
                        ResourceKey<Level> dimTo = OGEWirelessData.dimKey(buf.readIdentifier());
                        long posTo = buf.readLong();
                        pipes.add(new PipeView(id, dimFrom, posFrom, dimTo, posTo));
                    }
                    return new OGELogisticsPipeSyncPayload(pipes);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OGELogisticsPipeSyncPayload payload) {
                    buf.writeVarInt(payload.pipes().size());
                    for (PipeView pipe : payload.pipes()) {
                        buf.writeUUID(pipe.id());
                        buf.writeIdentifier(pipe.dimFrom().identifier());
                        buf.writeLong(pipe.posFrom());
                        buf.writeIdentifier(pipe.dimTo().identifier());
                        buf.writeLong(pipe.posTo());
                    }
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record PipeView(UUID id, ResourceKey<Level> dimFrom, long posFrom,
                           ResourceKey<Level> dimTo, long posTo) {
    }
}
