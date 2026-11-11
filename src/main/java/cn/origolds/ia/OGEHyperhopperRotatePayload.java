package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务器：旋转金源超级漏斗的输入端 / 输出端朝向。
 *
 * <p>不携带“当前朝向”，只带“转哪个端、绕哪条轴、顺还是逆”——服务端拿玩家的实际
 * 坐标现算“玩家—漏斗”这条连线轴，再走 {@link OGEHyperhopperBlock#rotateEnd}
 * 重新校验空手、方块类型与距离，客户端无法借这个包操作任意坐标的方块。</p>
 *
 * @param pos    目标方块坐标（{@link net.minecraft.core.BlockPos#asLong()} 打包）
 * @param target {@link OGEHyperhopperBlock#TARGET_INPUT} 或 {@link OGEHyperhopperBlock#TARGET_OUTPUT}
 * @param roll   {@code true} 绕“玩家—漏斗”连线轴转（Ctrl+Alt）；
 *               {@code false} 绕与连线轴垂直的水平轴转，端面沿竖直平面走（Ctrl）
 * @param ccw    {@code true} 逆时针（左键），{@code false} 顺时针（右键）
 */
public record OGEHyperhopperRotatePayload(long pos, int target, boolean roll, boolean ccw)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OGEHyperhopperRotatePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
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
                    buf.writeBoolean(payload.roll());
                    buf.writeBoolean(payload.ccw());
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
