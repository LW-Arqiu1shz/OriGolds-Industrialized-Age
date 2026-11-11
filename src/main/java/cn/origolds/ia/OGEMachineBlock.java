package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 金源能量方形机器方块：既作为发生装置（产 OGE），也作为储能装置（如电池）。
 * 产率、容量与机器类型通过构造参数配置。
 *
 * <p>带燃料槽的机器（火驱、魔驱）右键打开原料 UI；风驱随时随地工作；
 * 水驱只在接触水时工作。</p>
 */
public class OGEMachineBlock extends Block implements EntityBlock {

    private final int producePerSecond;
    private final int capacity;
    private final OGEMachineKind kind;

    public OGEMachineBlock(int producePerSecond, int capacity, OGEMachineKind kind, BlockBehaviour.Properties properties) {
        super(properties);
        this.producePerSecond = producePerSecond;
        this.capacity = capacity;
        this.kind = kind;
    }

    public int getProducePerSecond() {
        return producePerSecond;
    }

    public int getCapacity() {
        return capacity;
    }

    public OGEMachineKind getKind() {
        return kind;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OGEMachineBlockEntity(pos, state);
    }

    /**
     * 右键打开带燃料槽的机器（火驱/魔驱）的 UI。风驱/水驱/电池无 UI，
     * 此时放行（PASS），不影响在方块旁放置其他物品或方块。
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof OGEMachineBlockEntity machine && machine.getKind().isFueled()) {
            if (!level.isClientSide()) {
                player.openMenu(machine, pos);
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    /**
     * 创造模式挖掘：原版路径既不走过战利品表也不会调 {@link #playerDestroy}，
     * 带电机器会连电量直接消失。这里在破坏前（方块实体尚在）把带 OGE 的
     * 本方块物品弹出：电量 {@code >0} 才掉落，0 电量保持原版“无掉落”行为。
     * 生存模式不在此处理（战利品表的 {@code copy_oge_stored} 负责保留电量）。
     * 另：机器被彩虹标记点亮时，砸掉（任何模式）返还那颗投入的魔金之核。
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (player.isCreative() && be instanceof IOGEEnergyCacheHolder holder) {
                int stored = holder.getOGECachedStorage().getOGEStored();
                if (stored > 0) {
                    ItemStack drop = new ItemStack(this);
                    drop.set(ModDataComponents.OGE_STORED.get(), stored);
                    popResource(level, pos, drop);
                }
            }
            OGERainbowMarks.dropInvestedCore(level, pos, be);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public void playerDestroy(Level level, Player player, BlockPos pos, BlockState state, BlockEntity be, ItemStack tool) {
        if (be instanceof OGEMachineBlockEntity machine) {
            for (int i = 0; i < machine.getContainerSize(); i++) {
                ItemStack item = machine.getItem(i);
                if (!item.isEmpty()) {
                    popResource(level, pos, item);
                }
            }
        }
        super.playerDestroy(level, player, pos, state, be, tool);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (type == ModBlockEntities.OGE_MACHINE.get()) {
            return (l, p, s, be) -> ((OGEMachineBlockEntity) be).tick();
        }
        return null;
    }
}
