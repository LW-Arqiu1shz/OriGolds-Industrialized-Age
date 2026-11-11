package cn.origolds.ia;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务器：金源红石脉冲钟界面上的按钮指令。
 *
 * <p>报文体是“动作 + 刻数”，四个动作覆盖界面上的全部按钮语义：</p>
 * <ul>
 *   <li>{@link #ACTION_SET}：按输入的刻数设定周期并开始计时，{@code ticks <= 0} 表示停止；</li>
 *   <li>{@link #ACTION_PAUSE}：暂停，剩余时间与表盘留在原地；</li>
 *   <li>{@link #ACTION_RESUME}：从暂停处继续；</li>
 *   <li>{@link #ACTION_RESET}：把剩余时间拉回上限，{@code ticks > 0} 时顺便把上限
 *       改成这个值，是否正在流逝保持不变；</li>
 *   <li>{@link #ACTION_SAVE}：玩家在输入框里填了时长却没点按钮就退出界面，把填的内容
 *       记下来，同样不改变是否正在流逝。</li>
 * </ul>
 *
 * <p>报文同样不携带坐标：服务端只认玩家当前打开的 {@link OGEClockMenu}，
 * 客户端无法借这个包去操作任意坐标的方块。</p>
 */
public record OGEClockCommandPayload(int action, int ticks) implements CustomPacketPayload {

    /** 设定周期并开始计时（{@code ticks <= 0} 表示停止计时）。 */
    public static final int ACTION_SET = 0;

    /** 暂停。 */
    public static final int ACTION_PAUSE = 1;

    /** 继续。 */
    public static final int ACTION_RESUME = 2;

    /** 重置回上限，不改变流逝状态。 */
    public static final int ACTION_RESET = 3;

    /** 退出界面时把输入框里的时长记下来（不改变是否正在计时）。 */
    public static final int ACTION_SAVE = 4;

    public static final CustomPacketPayload.Type<OGEClockCommandPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    OriGoldsIndustrializedAge.MOD_ID, "oge_clock_command"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OGEClockCommandPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OGEClockCommandPayload decode(RegistryFriendlyByteBuf buf) {
                    return new OGEClockCommandPayload(buf.readVarInt(), buf.readVarInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OGEClockCommandPayload payload) {
                    buf.writeVarInt(payload.action());
                    buf.writeVarInt(payload.ticks());
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
