package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端→服务器：调整金源鼓风机的距离档 / 力量档。
 *
 * <p>按键是 Ctrl（力量档还要叠 Alt），这两个修饰键都不是原版会随交互包一起上传的，
 * 所以判定只能在客户端做：客户端按住 Ctrl 左键/右键指向鼓风机时发这个包，
 * 服务端 {@link OGEBlowerBlock#adjustGear} 再校验一次并改档。</p>
 *
 * <ul>
 *   <li>{@code distanceStep = +1}：Ctrl+左键，距离档 +1；</li>
 *   <li>{@code distanceStep = -1}：Ctrl+右键，距离档 -1；</li>
 *   <li>{@code powerStep = +1}：Ctrl+Alt+左键，力量档 +1；</li>
 *   <li>{@code powerStep = -1}：Ctrl+Alt+右键，力量档 -1。</li>
 * </ul>
 *
 * @param pos          鼓风机的方块坐标（{@link net.minecraft.core.BlockPos#asLong()} 打包）
 * @param distanceStep 距离档变化量，只会是 -1 / 0 / +1
 * @param powerStep    力量档变化量，只会是 -1 / 0 / +1
 */
public record OGEBlowerGearPayload(long pos, int distanceStep, int powerStep) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OGEBlowerGearPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(
                    OriGoldsIndustrializedAge.MOD_ID, "oge_blower_gear"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OGEBlowerGearPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OGEBlowerGearPayload decode(RegistryFriendlyByteBuf buf) {
                    return new OGEBlowerGearPayload(buf.readLong(), buf.readVarInt(), buf.readVarInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OGEBlowerGearPayload payload) {
                    buf.writeLong(payload.pos());
                    buf.writeVarInt(payload.distanceStep());
                    buf.writeVarInt(payload.powerStep());
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
