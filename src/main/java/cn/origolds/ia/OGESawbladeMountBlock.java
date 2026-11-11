package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
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

import javax.annotation.Nullable;

/**
 * 金源锯片台方块：装载近战武器/圆锯片的切割机器。
 *
 * <p>空手右键或拿任何物品右键都会打开锯片台界面（物品不消耗、不放回，直接开界面）。
 * 服务端每刻由 {@link BlockEntityTicker} 驱动 {@link OGESawbladeMountBlockEntity#tick()}。</p>
 *
 * <p>被破坏/被替换时的善后（掉下钉着的锯片实体、吐出储经验、解绑志愿者、掉落槽内物品）
 * 全部在 {@link OGESawbladeMountBlockEntity#preRemoveSideEffects(BlockPos, BlockState)}
 * 里做——26.x 的方块已经没有 {@code Block#onRemove} 挂点，方块实体随方块移除时的
 * 副作用统一走方块实体的这个钩子。</p>
 */
public final class OGESawbladeMountBlock extends Block implements EntityBlock {

    public OGESawbladeMountBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OGESawbladeMountBlockEntity(pos, state);
    }

    /** 只有服务端挂刻。 */
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide() || type != ModBlockEntities.OGE_SAWBLADE_MOUNT.get()) {
            return null;
        }
        return (l, p, s, be) -> {
            if (be instanceof OGESawbladeMountBlockEntity mount) {
                mount.tick();
            }
        };
    }

    /** 空手右键：打开锯片台界面。 */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level.getBlockEntity(pos) instanceof OGESawbladeMountBlockEntity mount) {
            if (!level.isClientSide()) {
                player.openMenu(mount, pos);
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    /** 手持任意物品右键：同样直接开界面，物品不会被用出去。 */
    @Override
    protected InteractionResult useItemOn(ItemStack held, BlockState state, Level level, BlockPos pos,
                                          Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (level.getBlockEntity(pos) instanceof OGESawbladeMountBlockEntity mount) {
            if (!level.isClientSide()) {
                player.openMenu(mount, pos);
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }
}
