package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端→服务器：旋转金源超级漏斗的输入端 / 输出端朝向。
 *
 * <p>不携带“当前朝向”，只带“转哪个端、用哪个轴、顺还是逆”——服务端拿玩家的实际
 * 坐标现算参考系，再走 {@link OGEHyperhopperBlock#rotateEnd} 重新校验空手、
 * 方块类型与距离，客户端无法借这个包操作任意坐标的方块。</p>
 *
 * @param pos    目标方块坐标（{@link net.minecraft.core.BlockPos#asLong()} 打包）
 * @param target {@link OGEHyperhopperBlock#TARGET_INPUT} 或 {@link OGEHyperhopperBlock#TARGET_OUTPUT}
 * @param alt    {@code true}（按住 Alt）绕“指向玩家的水平轴”翻滚；{@code false} 绕垂直视线的水平轴俯仰
 * @param ccw    {@code true} 逆时针（左键），{@code false} 顺时针（右键）
 */
public record OGEHyperhopperRotatePayload(long pos, int target, boolean alt, boolean ccw)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OGEHyperhopperRotatePayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(
                    OriGoldsIndustrializedAge.MOD_ID, "oge_hyperhopper_rotate"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OGEHyperhopperRotatePayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OGEHyperhopperRotatePayload decode(RegistryFriendlyByteBuf buf) {
                    return new OGEHyperhopperRotatePayload(buf.readLong(), buf.readVarInt(),
                            buf.readBoolean(), buf.readBoolean());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OGEHyperhopperRotatePayload payload) {
                    buf.writeLong(payload.pos());
                    buf.writeVarInt(payload.target());
                    buf.writeBoolean(payload.alt());
                    buf.writeBoolean(payload.ccw());
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
