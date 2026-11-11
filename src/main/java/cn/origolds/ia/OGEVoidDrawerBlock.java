package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 金源虚空抽屉方块：金属柜体，正面是带金芯的抽屉面板。
 *
 * <p>放置时正面自动水平朝向玩家（只看南北东西四个水平方向，把手永远横着对着玩家镜头）；
 * 空手右键打开抽屉 UI（单类型、729 组、逐格解锁、自动码满，
 * 具体存取规则见 {@link OGEVoidDrawerBlockEntity}）；
 * 背面可被比较器读取，信号强度按物品总数占容量的比例输出。</p>
 */
public final class OGEVoidDrawerBlock extends Block implements EntityBlock, OGEItemBindableBlock {

    /** 正面朝向（只允许南北东西四个水平方向，把手永远横着朝向玩家）。 */
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    public OGEVoidDrawerBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /** 放置时正面水平朝向玩家：只看玩家所在的水平方向，不看俯仰（同原版熔炉）。 */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, net.minecraft.world.level.block.Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, net.minecraft.world.level.block.Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OGEVoidDrawerBlockEntity(pos, state);
    }

    /**
     * 服务端每 tick 驱动一次抽屉的排序循环；客户端镜像槽位完全由同步包维护，
     * 不需要（也不应该）在本地重排。
     */
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide() || type != ModBlockEntities.OGE_VOID_DRAWER.get()) {
            return null;
        }
        return (l, p, s, be) -> ((OGEVoidDrawerBlockEntity) be).tick();
    }

    /** 空手右键打开抽屉 UI。 */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level.getBlockEntity(pos) instanceof OGEVoidDrawerBlockEntity drawer) {
            if (!level.isClientSide()) {
                player.openMenu(drawer, pos);
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
        return level.getBlockEntity(pos) instanceof OGEVoidDrawerBlockEntity drawer
                ? drawer.getComparatorSignal()
                : 0;
    }

    /** 创造模式取色（中键）：带上抽屉当前存放的“种类 + 总数”，放置后内容还原。 */
    @Override
    protected ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
        ItemStack stack = super.getCloneItemStack(level, pos, state, includeData);
        if (level.getBlockEntity(pos) instanceof OGEVoidDrawerBlockEntity drawer) {
            OGEVoidDrawerContents contents = drawer.getStoredContents();
            if (contents != null) {
                stack.set(ModDataComponents.VOID_DRAWER_CONTENTS.get(), contents);
            }
        }
        return stack;
    }

    /** 创造模式直接敲掉抽屉时，把内部存储以掉落物形式吐出来（生存正常掉落自带组件）。 */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && player.isCreative()
                && level.getBlockEntity(pos) instanceof OGEVoidDrawerBlockEntity drawer) {
            OGEVoidDrawerContents contents = drawer.getStoredContents();
            if (contents != null) {
                ItemStack drop = new ItemStack(this);
                drop.set(ModDataComponents.VOID_DRAWER_CONTENTS.get(), contents);
                popResource(level, pos, drop);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
