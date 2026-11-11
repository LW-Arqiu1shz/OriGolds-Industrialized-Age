package cn.origolds.ia;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.Vec3;

/**
 * 金源销毁站（Origold Voiding Station）方块。
 *
 * <p>它会读取自身正上方那一格：若上面是容器（末影箱除外）就读容器里的物品，
 * 同时也会统计落在那一格空间里的掉落物。某个物品在模式时间内“数量一直没有
 * 减少”时，整类物品会被销毁（详见方块实体 {@link OGEVoidingStationBlockEntity}）。</p>
 *
 * <p>销毁等待时间分五档：1 / 2 / 5 / 10 / 20 tick，分别对应四侧贴图
 * {@code 1tbin_side} … {@code 20tbin_side}。放下时默认是 5tick 模式；
 * <b>空手 Alt+左键降低延迟、空手 Alt+右键增加延迟</b>，切换结果直接反映在贴图上，
 * 到达两端（1 tick / 20 tick）时夹住不再变化。</p>
 *
 * <p>Alt 不是原版交互包会携带的修饰键，所以按键判定在客户端完成
 * （见 {@code OGEVoidingStationClient}），服务端收到
 * {@link OGEVoidingStationModePayload} 后由 {@link #adjustMode} 重新校验并改档。</p>
 */
public final class OGEVoidingStationBlock extends Block implements EntityBlock {

    /** 允许远程改档的最大距离平方（8 格）。 */
    private static final double MAX_ADJUST_DISTANCE_SQ = 64.0;

    /** 五档等待时间（tick），与五张 "Ntbin_side" 侧面贴图一一对应。 */
    public static final int[] MODE_TICKS = {1, 2, 5, 10, 20};

    /** 默认档位下标：放下时是 5tick 模式。 */
    public static final int DEFAULT_MODE = 2;

    /** 档位方块状态：0~4 分别代表 1 / 2 / 5 / 10 / 20 tick。 */
    public static final IntegerProperty MODE = IntegerProperty.create("mode", 0, MODE_TICKS.length - 1);

    public OGEVoidingStationBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(MODE, DEFAULT_MODE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(MODE);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return simpleCodec(OGEVoidingStationBlock::new);
    }

    /** 放下时固定为默认档位（5tick），不跟随玩家朝向。 */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState();
    }

    /** 把档位下标换算成等待的 tick 数，越界时夹到合法范围。 */
    public static int modeTicks(int mode) {
        int index = mode < 0 ? 0 : Math.min(mode, MODE_TICKS.length - 1);
        return MODE_TICKS[index];
    }

    /**
     * 服务端侧调整档位，由客户端 Alt+左键/右键的报文触发（见 {@link OGEVoidingStationModePayload}）。
     * 方块自身不再提供“空手右键循环切档”。
     *
     * <p>客户端只负责判定 Alt（服务端读不到键盘），真正生效前这里把所有条件重新校验一遍：
     * 手上必须空着、必须真的指着金源销毁站、且在 8 格以内。档位到两端时夹住不变，
     * 但仍播报一次当前等待时间，让玩家知道已经到底。</p>
     *
     * @param player    发起操作的玩家
     * @param packedPos 目标方块坐标（{@link BlockPos#asLong()} 打包）
     * @param delta     档位变化量：-1 降低延迟，+1 增加延迟
     */
    public static void adjustMode(ServerPlayer player, long packedPos, int delta) {
        if (delta == 0) {
            return;
        }
        BlockPos pos = BlockPos.of(packedPos);
        Level level = player.level();
        if (!level.isLoaded(pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof OGEVoidingStationBlock)) {
            return;
        }
        // “空手”只认主手；主手有物品时 Alt+左/右键本来就该交给物品自己处理。
        if (!player.getMainHandItem().isEmpty()) {
            return;
        }
        if (player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_ADJUST_DISTANCE_SQ) {
            return;
        }
        int current = state.getValue(MODE);
        int next = Math.max(0, Math.min(MODE_TICKS.length - 1, current + delta));
        if (next != current) {
            level.setBlock(pos, state.setValue(MODE, next), Block.UPDATE_ALL);
        }
        player.displayClientMessage(
                Component.translatable("message.origolds_ia.voiding_mode", modeTicks(next)), true);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OGEVoidingStationBlockEntity(pos, state);
    }

    /** 只在服务端驱动销毁逻辑。 */
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || type != ModBlockEntities.OGE_VOIDING_STATION.get()) {
            return null;
        }
        return (l, p, s, be) -> {
            if (be instanceof OGEVoidingStationBlockEntity station) {
                station.tick();
            }
        };
    }
}
