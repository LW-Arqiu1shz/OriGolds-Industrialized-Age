package cn.origolds.ia;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

/**
 * 金源圆锯刃槽（Origold Sawblade Mount，{@code origolds_ia:origold_sawblade_mount}）。
 *
 * <p>它本身是一台普普通通的完整方块：顶面与底面用 {@code circular_saw_top_bottom}，
 * 四个侧面用 {@code circular_saw_sides}，不朝向任何方向，放下去就是正的。</p>
 *
 * <h2>装载圆锯刃片</h2>
 * <p>手持{@link OGECircularSawbladeItem 圆锯刃片} <b>Shift+右键</b>它的某个面，
 * 就会在那个面上装一片{@link OGESawbladeEntity 刀刃}——刀刃是独立实体，
 * 待在这个面外侧那一格里（那格有什么方块都不影响，也能同格叠好几片），没有碰撞体积。
 * 装一次扣 1 点耐久，刃片留在手上。交互本体在
 * {@link OGESawbladeMountEvents}（潜行又拿着东西时原版根本不会调用方块的
 * {@code useItemOn}，只能在事件里做）。</p>
 *
 * <h2>界面</h2>
 * <p>右键（拿没拿东西都行，但不能潜行）打开 {@link OGESawbladeMountScreen}：
 * 顶部武器槽、中间经验读数与“取出”控件、底部废物槽。</p>
 *
 * <h2>拆下来给铁块</h2>
 * <p>单片刀刃靠“手持刃片 Shift+左键这个面”拆，掉 1 个铁块。这里负责另一半：
 * 刃槽被破坏时，把<b>还挂在它身上的刀刃实体</b>全部清掉，每片掉 1 个铁块；
 * 容器里的武器与废物掉回地面，库存的经验也砸成经验球散落原位。</p>
 */
public final class OGESawbladeMountBlock extends Block implements EntityBlock {

    public OGESawbladeMountBlock(net.minecraft.world.level.block.state.BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OGESawbladeMountBlockEntity(pos, state);
    }

    /** 服务端每刻驱动：刷新电网可用性缓存（刀刃们拿这份缓存决定转不转）。 */
    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                            BlockEntityType<T> type) {
        if (level.isClientSide || type != ModBlockEntities.SAWBLADE_MOUNT.get()) {
            return null;
        }
        return (l, p, s, be) -> {
            if (be instanceof OGESawbladeMountBlockEntity mount) {
                mount.tick();
            }
        };
    }

    /** 空手右键开界面。 */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level.getBlockEntity(pos) instanceof OGESawbladeMountBlockEntity mount) {
            if (!level.isClientSide) {
                player.openMenu(mount, pos);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return InteractionResult.PASS;
    }

    /** 拿着东西右键（没潜行）也开界面：圆锯刃片、武器、随便什么。 */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack held, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (level.getBlockEntity(pos) instanceof OGESawbladeMountBlockEntity mount) {
            if (!level.isClientSide) {
                player.openMenu(mount, pos);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    /**
     * 刃槽没了（被挖、被换、被活塞推走）：
     * 挂在它身上的刀刃实体一并清掉（每片掉 1 个铁块），
     * 武器与废物散落，库存经验砸成经验球。
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel) {
            for (OGESawbladeEntity blade : OGESawbladeEntity.findMountedOn(serverLevel, pos, null)) {
                blade.dropAndDiscard(serverLevel);
            }
            if (serverLevel.getBlockEntity(pos) instanceof OGESawbladeMountBlockEntity mount) {
                int xp = mount.getStoredExperience();
                if (xp > 0) {
                    ExperienceOrb.award(serverLevel, Vec3.atCenterOf(pos), xp);
                }
                // 绑定了的：把志愿者那边的绑定数据也立刻清掉。
                if (mount.getBoundVolunteerId() != null
                        && serverLevel.getEntity(mount.getBoundVolunteerId())
                        instanceof OGEVolunteerWatcherEntity volunteer) {
                    volunteer.removeBind(pos);
                }
            }
            Containers.dropContentsOnDestroy(state, newState, serverLevel, pos);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }
}
