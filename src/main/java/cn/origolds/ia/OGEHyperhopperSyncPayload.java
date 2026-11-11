package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务器→客户端：把金源超级漏斗的计时状态与单次漏取上限同步给打开界面的玩家。
 *
 * <p>刻数是每刻都在变的 {@code int}，量程超过菜单数据槽（线上按 short 传输），
 * 所以单开一个报文，由 {@link OGEHyperhopperMenu#broadcastChanges()} 在数值变化时下发。</p>
 *
 * @param totalTicks     当前间隔（刻）
 * @param remainingTicks 距离下次搬运还剩多少刻
 * @param running        是否正在按间隔搬运
 * @param limit          单次漏取上限（个）
 */
public record OGEHyperhopperSyncPayload(int totalTicks, int remainingTicks, boolean running, int limit)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OGEHyperhopperSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    OriGoldsIndustrializedAge.MOD_ID, "oge_hyperhopper_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OGEHyperhopperSyncPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OGEHyperhopperSyncPayload decode(RegistryFriendlyByteBuf buf) {
                    return new OGEHyperhopperSyncPayload(buf.readVarInt(), buf.readVarInt(),
                            buf.readBoolean(), buf.readVarInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OGEHyperhopperSyncPayload payload) {
                    buf.writeVarInt(payload.totalTicks());
                    buf.writeVarInt(payload.remainingTicks());
                    buf.writeBoolean(payload.running());
                    buf.writeVarInt(payload.limit());
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
