package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务器→客户端：把金源红石脉冲钟的倒计时状态同步给打开界面的玩家。
 *
 * <p>倒计时以游戏刻为单位、量程远超菜单数据槽（线上按 short 传输），
 * 所以单开一个报文，由 {@link OGEClockMenu#broadcastChanges()} 在数值变化时下发。</p>
 *
 * @param totalTicks     当前设定的周期（刻），0 表示未计时
 * @param remainingTicks 距离下次脉冲还剩多少刻
 * @param running        是否正在计时
 */
public record OGEClockSyncPayload(int totalTicks, int remainingTicks, boolean running)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OGEClockSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    OriGoldsIndustrializedAge.MOD_ID, "oge_clock_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OGEClockSyncPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OGEClockSyncPayload decode(RegistryFriendlyByteBuf buf) {
                    return new OGEClockSyncPayload(buf.readVarInt(), buf.readVarInt(), buf.readBoolean());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OGEClockSyncPayload payload) {
                    buf.writeVarInt(payload.totalTicks());
                    buf.writeVarInt(payload.remainingTicks());
                    buf.writeBoolean(payload.running());
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
