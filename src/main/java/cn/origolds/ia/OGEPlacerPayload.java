package cn.origolds.ia;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 客户端→服务器：金源放置器的全部编辑操作。
 *
 * <p>Alt / Ctrl 都不是原版交互包会携带的修饰键，所以按键判定只能在客户端做
 * （见 {@code OGEPlacerClient}）；服务端收到这个包后按 {@code action} 分派到
 * {@link OGEPlacerBlock} 的对应校验方法，一切条件重新验一遍。</p>
 *
 * <ul>
 *   <li>{@link #ACTION_BIND}：Alt+中键，绑定主手的方块 / 空手清空绑定；</li>
 *   <li>{@link #ACTION_ADJUST}：Ctrl+左/右键，{@code value} 为 ±1 的档位变化量；</li>
 *   <li>{@link #ACTION_SAVE}：投影放置模式 Alt+右键，带上整份投影列表；</li>
 *   <li>{@link #ACTION_DELETE}：投影模式或物流显示模式左键，{@code projections} 里只带
 *       一条要删除的投影（只用它的位置，状态 id 仅用来在服务端还原破坏音效与粒子）。</li>
 * </ul>
 *
 * @param pos         放置器的方块坐标（{@link BlockPos#asLong()} 打包）
 * @param action      动作类型，见上面的常量
 * @param value       档位变化量（仅 {@link #ACTION_ADJUST} 使用）
 * @param projections 投影列表（{@link #ACTION_SAVE} 与 {@link #ACTION_DELETE} 使用）
 */
public record OGEPlacerPayload(long pos, int action, int value, List<Entry> projections)
        implements CustomPacketPayload {

    /** Alt+中键：绑定/清空绑定。 */
    public static final int ACTION_BIND = 0;
    /** Ctrl+左键/右键：调整放置间隔。 */
    public static final int ACTION_ADJUST = 1;
    /** Alt+右键：保存投影列表。 */
    public static final int ACTION_SAVE = 2;
    /** 左键：删除一条投影。 */
    public static final int ACTION_DELETE = 3;

    public static final CustomPacketPayload.Type<OGEPlacerPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(
                    OriGoldsIndustrializedAge.MOD_ID, "oge_placer"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OGEPlacerPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OGEPlacerPayload decode(RegistryFriendlyByteBuf buf) {
                    long pos = buf.readLong();
                    int action = buf.readVarInt();
                    int value = buf.readVarInt();
                    int size = buf.readVarInt();
                    List<Entry> list = new ArrayList<>(Math.min(size, OGEPlacerBlock.MAX_PROJECTIONS));
                    for (int i = 0; i < size; i++) {
                        list.add(new Entry(buf.readLong(), buf.readVarInt()));
                    }
                    return new OGEPlacerPayload(pos, action, value, list);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OGEPlacerPayload payload) {
                    buf.writeLong(payload.pos());
                    buf.writeVarInt(payload.action());
                    buf.writeVarInt(payload.value());
                    buf.writeVarInt(payload.projections().size());
                    for (Entry entry : payload.projections()) {
                        buf.writeLong(entry.pos());
                        buf.writeVarInt(entry.stateId());
                    }
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 一条投影：位置 + 方块状态。
     *
     * <p>状态用方块状态注册表 id 传输（{@link Block#getId} / {@link Block#stateById}），
     * 比整份 NBT 小得多。id 来自客户端，属于不可信输入：{@link Block#stateById(int)}
     * 越界时返回 {@code null}，调用方必须先判空再使用（见
     * {@link #toProjection(Entry)}）。</p>
     */
    public record Entry(long pos, int stateId) {

        public static Entry of(BlockPos pos, BlockState state) {
            return new Entry(pos.asLong(), Block.getId(state));
        }

        public BlockPos blockPos() {
            return BlockPos.of(pos);
        }
    }

    /** 把一条网络投影还原成方块实体用的投影；状态 id 非法时返回 null。 */
    public static OGEPlacerBlockEntity.Projection toProjection(Entry entry) {
        BlockState state = Block.stateById(entry.stateId());
        if (state == null) {
            return null;
        }
        return new OGEPlacerBlockEntity.Projection(entry.blockPos(), state);
    }
}
