package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 资源发生器（沙砾 / 黏土 / 黑曜石……）的通用方块。
 *
 * <p>接入无线电网后，每 1 秒尝试消耗固定 OGE，在自身正上方生成 1 个目标方块；
 * OGE 不足或顶部被方块占用时不生成、不扣费。目标方块与单次消耗由构造参数决定，
 * 三个具体发生器共用同一方块实体类型 {@link ModBlockEntities#OGE_RESOURCE_GENERATOR}。</p>
 */
public final class OGEResourceGeneratorBlock extends Block implements EntityBlock {

    private final Block outputBlock;
    private final int costPerOperation;

    /**
     * @param outputBlock      每秒在顶部生成的方块（如沙砾块、黏土块、黑曜石）
     * @param costPerOperation 每次生成消耗的 OGE（单位 u）
     */
    public OGEResourceGeneratorBlock(Block outputBlock, int costPerOperation, BlockBehaviour.Properties properties) {
        super(properties);
        this.outputBlock = outputBlock;
        this.costPerOperation = costPerOperation;
    }

    /** 本发生器生成的目标方块。 */
    public Block outputBlock() {
        return outputBlock;
    }

    /** 每次生成消耗的 OGE。 */
    public int costPerOperation() {
        return costPerOperation;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OGEResourceGeneratorBlockEntity(pos, state);
    }

    /**
     * 贴着虹彩印记的发生器被玩家挖掉时返还 1 个魔金之核（贴一次扣的 1 点耐久等价于
     * 1 个核）；印记本身不掉落，避免复制。方块实体在破坏前仍可读到档位。
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide) {
            OGERainbowMarks.dropInvestedCore(level, pos, level.getBlockEntity(pos));
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (type == ModBlockEntities.OGE_RESOURCE_GENERATOR.get()) {
            return (l, p, s, be) -> {
                if (be instanceof OGEResourceGeneratorBlockEntity generator) {
                    generator.tick();
                }
            };
        }
        return null;
    }
}
