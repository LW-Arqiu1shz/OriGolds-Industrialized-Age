package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * 服务器→客户端：同步当前玩家刚选的管道端点（起点/终点），
 * 用于客户端在方块上绘制呼吸高亮框：起点青色、终点橙色。
 */
public record OGELogisticsSelectSyncPayload(boolean present, ResourceKey<Level> dim, long pos, boolean start)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OGELogisticsSelectSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(OriGoldsIndustrializedAge.MOD_ID, "oge_pipe_select_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OGELogisticsSelectSyncPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OGELogisticsSelectSyncPayload decode(RegistryFriendlyByteBuf buf) {
                    boolean present = buf.readBoolean();
                    if (!present) {
                        return new OGELogisticsSelectSyncPayload(false, null, 0L, false);
                    }
                    ResourceKey<Level> dim = OGEWirelessData.dimKey(buf.readResourceLocation());
                    long pos = buf.readLong();
                    boolean start = buf.readBoolean();
                    return new OGELogisticsSelectSyncPayload(true, dim, pos, start);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OGELogisticsSelectSyncPayload payload) {
                    buf.writeBoolean(payload.present());
                    if (payload.present()) {
                        buf.writeResourceLocation(payload.dim().location());
                        buf.writeLong(payload.pos());
                        buf.writeBoolean(payload.start());
                    }
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
