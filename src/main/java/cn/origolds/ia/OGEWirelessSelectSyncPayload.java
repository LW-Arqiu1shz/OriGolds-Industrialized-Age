package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * 服务器→客户端：同步当前玩家刚选的端点（用于客户端在方块上绘制呼吸高亮框）。
 */
public record OGEWirelessSelectSyncPayload(boolean present, ResourceKey<Level> dim, long pos, boolean start)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OGEWirelessSelectSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(OriGoldsIndustrializedAge.MOD_ID, "oge_wireless_select_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OGEWirelessSelectSyncPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OGEWirelessSelectSyncPayload decode(RegistryFriendlyByteBuf buf) {
                    boolean present = buf.readBoolean();
                    if (!present) {
                        return new OGEWirelessSelectSyncPayload(false, null, 0L, false);
                    }
                    ResourceKey<Level> dim = OGEWirelessData.dimKey(buf.readResourceLocation());
                    long pos = buf.readLong();
                    boolean start = buf.readBoolean();
                    return new OGEWirelessSelectSyncPayload(true, dim, pos, start);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OGEWirelessSelectSyncPayload payload) {
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
