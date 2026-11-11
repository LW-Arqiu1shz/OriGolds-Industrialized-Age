package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端→服务器：金源超级漏斗界面上的按钮与文本框指令。
 *
 * <p>语义与金源红石脉冲钟的 {@link OGEClockCommandPayload} 一致，另外多了
 * {@link #limit()}（单次漏取上限）。报文不携带坐标，服务端只认玩家当前打开的
 * {@link OGEHyperhopperMenu}。</p>
 *
 * @param action 动作，见 {@link #ACTION_SET} 等常量
 * @param ticks  界面三个文本框折算出来的刻数；{@code <= 0} 表示“不改动间隔”
 * @param limit  灰色文本框里的单次漏取上限；{@code <= 0} 表示“不改动上限”
 */
public record OGEHyperhopperCommandPayload(int action, int ticks, int limit)
        implements CustomPacketPayload {

    /** 设定间隔并开始吸入。 */
    public static final int ACTION_SET = 0;

    /** 暂停吸入。 */
    public static final int ACTION_PAUSE = 1;

    /** 从暂停处继续；{@code ticks > 0} 时顺便更新间隔。 */
    public static final int ACTION_RESUME = 2;

    /** 把剩余时间拉回上限；{@code ticks > 0} 时顺便更新间隔。 */
    public static final int ACTION_RESET = 3;

    /** 退出界面时把文本框内容记下来（不改变是否正在吸入）。 */
    public static final int ACTION_SAVE = 4;

    /** 只改单次漏取上限。 */
    public static final int ACTION_LIMIT = 5;

    public static final CustomPacketPayload.Type<OGEHyperhopperCommandPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(
                    OriGoldsIndustrializedAge.MOD_ID, "oge_hyperhopper_command"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OGEHyperhopperCommandPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OGEHyperhopperCommandPayload decode(RegistryFriendlyByteBuf buf) {
                    return new OGEHyperhopperCommandPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OGEHyperhopperCommandPayload payload) {
                    buf.writeVarInt(payload.action());
                    buf.writeVarInt(payload.ticks());
                    buf.writeVarInt(payload.limit());
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
