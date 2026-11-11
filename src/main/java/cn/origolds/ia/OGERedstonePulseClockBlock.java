package cn.origolds.ia;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 金源红石脉冲钟（Origold Redstone-Pulse Clock）方块。
 *
 * <p>顶面与底面固定使用 {@code top_bottom}，四个侧面按倒计时进度在
 * {@code clock0} ~ {@code clock7} 之间切换：每走完八分之一周期表盘前进一格
 * （例如 1 小时的倒计时走到 30 分钟时是 {@code clock4}），倒计时归零后
 * 立刻从头开始。侧面贴图还分“时间在流逝”与“时间不在流逝”两套：
 * {@link #RUNNING} 为 {@code true} 时用 {@code clock#_active}（计时中），
 * 为 {@code false} 时用原来的 {@code clock#}（暂停或未计时），进度档位两者共用。</p>
 *
 * <p>它是一个红石信号源：倒计时归零时向四周发出一次持续 2 刻的 15 级脉冲，
 * 红石粉可以直接连到它身上（{@link #isSignalSource} 返回 {@code true}，
 * 原版红石线的连接判定就认这个）。右键打开界面，在三个输入框里填时、分、秒，
 * 点顶部居中的“▷”开始计时（详见 {@link OGEClockScreen} 与
 * {@link OGERedstonePulseClockBlockEntity}）。</p>
 */
public final class OGERedstonePulseClockBlock extends Block implements EntityBlock {

    /** 侧面贴图档数：clock0 ~ clock7，对应倒计时的 0/8 ~ 7/8。 */
    public static final int PROGRESS_STEPS = 8;

    /** 倒计时进度，0 ~ 7，分别对应 clock0 ~ clock7。 */
    public static final IntegerProperty PROGRESS = IntegerProperty.create("progress", 0, PROGRESS_STEPS - 1);

    /** 时间是否正在流逝；决定侧面的表盘用“计时中”还是“已停下”的那套贴图。 */
    public static final BooleanProperty RUNNING = BooleanProperty.create("running");

    public OGERedstonePulseClockBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(this.stateDefinition.any()
                .setValue(PROGRESS, 0)
                .setValue(RUNNING, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(PROGRESS, RUNNING);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return simpleCodec(OGERedstonePulseClockBlock::new);
    }

    /** 放下时进度归零（clock0），不跟随玩家朝向。 */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState();
    }

    /** 红石信号源：原版红石线据此判定能不能连到这块方块上。 */
    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    /** 脉冲期间对四周输出 15 级信号。 */
    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        return isPulsing(level, pos) ? 15 : 0;
    }

    /** 同 {@link #getSignal}：脉冲期间也向贴着的方块输出强信号。 */
    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        return isPulsing(level, pos) ? 15 : 0;
    }

    private static boolean isPulsing(BlockGetter level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof OGERedstonePulseClockBlockEntity clock && clock.isPulsing();
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OGERedstonePulseClockBlockEntity(pos, state);
    }

    /** 只在服务端驱动：倒计时、换表盘、发脉冲。 */
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide() || type != ModBlockEntities.OGE_REDSTONE_PULSE_CLOCK.get()) {
            return null;
        }
        return (l, p, s, be) -> {
            if (be instanceof OGERedstonePulseClockBlockEntity clock) {
                clock.tick();
            }
        };
    }

    /** 右键打开计时界面（不需要手持任何物品）。 */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level.getBlockEntity(pos) instanceof OGERedstonePulseClockBlockEntity clock) {
            if (!level.isClientSide()) {
                player.openMenu(clock, pos);
            }
            return level.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER;
        }
        return InteractionResult.PASS;
    }
}
