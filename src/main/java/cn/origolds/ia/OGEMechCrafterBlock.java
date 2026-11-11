package cn.origolds.ia;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 金源机械合成台（Origold Mech-Crafter）：一台 3×3 的自动合成机器。
 *
 * <p>界面里有一块 3×3 的合成格与 27 格储料仓。玩家左键点合成格把"要合成的配方
 * 格"锁定成某件物品，右键清除锁定；锁定后储料仓只收与锁定物同种的物品，
 * 机器持续按锁定的 3×3 矩阵查配方，凑齐材料并扣 1 点 OGE 后自动合成一次，
 * 产物从顶面输出（见 {@link OGEMechCrafterBlockEntity}）。</p>
 *
 * <p>放置时正面水平朝向玩家；可被比较器读取（按储料仓已用格数输出 0~15）。
 * 破坏时储料仓内容散落到地面——26.x 没有 {@code Block#onRemove}，该行为
 * 由 {@link OGEMechCrafterBlockEntity#preRemoveSideEffects} 承担。</p>
 */
public final class OGEMechCrafterBlock extends Block implements EntityBlock {

    /** 正面朝向（只允许南北东西四个水平方向）。 */
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    public OGEMechCrafterBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return simpleCodec(OGEMechCrafterBlock::new);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OGEMechCrafterBlockEntity(pos, state);
    }

    /** 只有服务端跑合成循环；客户端界面与浮空展品全凭方块实体同步包驱动。 */
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide() || type != ModBlockEntities.OGE_MECH_CRAFTER.get()) {
            return null;
        }
        return (l, p, s, be) -> {
            if (be instanceof OGEMechCrafterBlockEntity crafter) {
                crafter.tick();
            }
        };
    }

    /** 空手右键打开合成台界面。 */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level.getBlockEntity(pos) instanceof OGEMechCrafterBlockEntity crafter) {
            if (!level.isClientSide()) {
                player.openMenu(crafter, pos);
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
        return level.getBlockEntity(pos) instanceof OGEMechCrafterBlockEntity crafter
                ? crafter.getComparatorSignal()
                : 0;
    }
}
