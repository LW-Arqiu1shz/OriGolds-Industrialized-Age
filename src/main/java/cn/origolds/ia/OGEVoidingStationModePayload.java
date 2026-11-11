package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务器：调整金源销毁站的等待档位。
 *
 * <p>按键是 Alt，而 Alt 不是原版会随交互包一起上传的修饰键，所以判定只能在客户端做：
 * 客户端按住 Alt 左键/右键指向销毁站时发这个包，服务端再校验一次并改档位。
 * {@code delta = -1} 表示降低延迟（Alt+左键），{@code +1} 表示增加延迟（Alt+右键）。</p>
 *
 * @param pos   销毁站的方块坐标（{@link net.minecraft.core.BlockPos#asLong()} 打包）
 * @param delta 档位变化量，只会是 -1 或 +1
 */
public record OGEVoidingStationModePayload(long pos, int delta) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OGEVoidingStationModePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    OriGoldsIndustrializedAge.MOD_ID, "oge_voiding_station_mode"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OGEVoidingStationModePayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OGEVoidingStationModePayload decode(RegistryFriendlyByteBuf buf) {
                    return new OGEVoidingStationModePayload(buf.readLong(), buf.readVarInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OGEVoidingStationModePayload payload) {
                    buf.writeLong(payload.pos());
                    buf.writeVarInt(payload.delta());
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
