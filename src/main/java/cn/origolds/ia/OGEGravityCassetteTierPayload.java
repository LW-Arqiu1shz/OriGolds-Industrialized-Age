package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务器：调整金源引力匣的吸附档位（0 / 1 / 2 / 3 / 4 档，0 档是“相邻方块”）。
 *
 * <p>按键是 Alt，它不是原版会随交互包一起上传的修饰键，所以判定只能在客户端做：
 * 客户端按住 Alt 左键/右键指向引力匣时发这个包，服务端
 * {@link OGEGravityCassetteBlock#adjustTier} 再校验一次并改档。</p>
 *
 * <ul>
 *   <li>{@code delta = -1}：Alt+左键，降档（左下），范围缩小，到 0 档即“相邻方块”；</li>
 *   <li>{@code delta = +1}：Alt+右键，升档（右上），范围扩大。</li>
 * </ul>
 *
 * @param pos   引力匣的方块坐标（{@link net.minecraft.core.BlockPos#asLong()} 打包）
 * @param delta 档位变化量，只会是 -1 / +1
 */
public record OGEGravityCassetteTierPayload(long pos, int delta) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OGEGravityCassetteTierPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    OriGoldsIndustrializedAge.MOD_ID, "oge_gravity_cassette_tier"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OGEGravityCassetteTierPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OGEGravityCassetteTierPayload decode(RegistryFriendlyByteBuf buf) {
                    return new OGEGravityCassetteTierPayload(buf.readLong(), buf.readVarInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OGEGravityCassetteTierPayload payload) {
                    buf.writeLong(payload.pos());
                    buf.writeVarInt(payload.delta());
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
