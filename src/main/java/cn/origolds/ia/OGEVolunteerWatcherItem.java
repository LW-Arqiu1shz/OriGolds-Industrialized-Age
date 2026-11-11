package cn.origolds.ia;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 放置挂机志愿者的物品。 */
public final class OGEVolunteerWatcherItem extends Item {

    public OGEVolunteerWatcherItem(Properties properties) {
        super(properties);
    }

    public static int getMode(ItemStack stack) {
        Integer mode = stack.get(ModDataComponents.VOLUNTEER_MODE.get());
        return mode != null && mode == OGEVolunteerWatcherEntity.MOB_TARGET_MODE
                ? OGEVolunteerWatcherEntity.MOB_TARGET_MODE
                : OGEVolunteerWatcherEntity.PLAYER_MODE;
    }

    /**
     * 清掉历史存档里冗余的默认值组件。
     *
     * <p>早期版本收回志愿者时把"玩家模式"（本来就是默认值）也写进了组件，带着这个组件的
     * 物品与新合成的物品组件表不同，堆不到一起。现在 {@code copyStateToItem} 只写有效信息，
     * 这里负责把存档 / 创造栏里剩下的那种"记了个默认值"的组件规整掉，老物品也能重新合堆。</p>
     */
    @Override
    public void verifyComponentsAfterLoad(ItemStack stack) {
        super.verifyComponentsAfterLoad(stack);
        Integer mode = stack.get(ModDataComponents.VOLUNTEER_MODE.get());
        if (mode != null && mode == OGEVolunteerWatcherEntity.PLAYER_MODE) {
            stack.remove(ModDataComponents.VOLUNTEER_MODE.get());
        }
        List<OGEVolunteerBindEntry> binds = stack.get(ModDataComponents.VOLUNTEER_BINDS.get());
        if (binds != null && binds.isEmpty()) {
            stack.remove(ModDataComponents.VOLUNTEER_BINDS.get());
        }
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getClickedFace() == Direction.DOWN) {
            return InteractionResult.FAIL;
        }
        BlockPlaceContext placeContext = new BlockPlaceContext(context);
        BlockPos pos = placeContext.getClickedPos();
        Vec3 center = Vec3.atBottomCenterOf(pos);
        AABB bounds = ModEntities.VOLUNTEER_WATCHER.get().getDimensions()
                .makeBoundingBox(center.x, center.y, center.z);
        if (!context.getLevel().noCollision(null, bounds)
                || !context.getLevel().getEntities(null, bounds).isEmpty()) {
            return InteractionResult.FAIL;
        }

        ItemStack stack = context.getItemInHand();
        if (context.getLevel() instanceof ServerLevel serverLevel) {
            OGEVolunteerWatcherEntity watcher = ModEntities.VOLUNTEER_WATCHER.get().create(
                    serverLevel,
                    EntityType.createDefaultStackConfig(serverLevel, stack, context.getPlayer()),
                    pos,
                    MobSpawnType.SPAWN_EGG,
                    true,
                    true);
            if (watcher == null) {
                return InteractionResult.FAIL;
            }
            float rotation = Mth.wrapDegrees(
                    Mth.floor((context.getRotation() - 180.0F + 22.5F) / 45.0F) * 45.0F);
            watcher.moveTo(watcher.getX(), watcher.getY(), watcher.getZ(), rotation, 0.0F);
            watcher.setYBodyRot(rotation);
            watcher.setYHeadRot(rotation);
            watcher.setMode(getMode(stack));
            watcher.restoreBindsFromItem(stack);
            serverLevel.addFreshEntityWithPassengers(watcher);
            serverLevel.playSound(null, watcher.getX(), watcher.getY(), watcher.getZ(),
                    SoundEvents.ARMOR_STAND_PLACE, SoundSource.BLOCKS, 0.75F, 0.8F);
        }
        if (context.getPlayer() == null || !context.getPlayer().getAbilities().instabuild) {
            stack.shrink(1);
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        OGETooltips.add(tooltip, flag,
                "tooltip.origolds_ia.volunteer_watcher",
                "tooltip.origolds_ia.ctrl.volunteer_watcher",
                "tooltip.origolds_ia.bs.volunteer_watcher");
        String modeKey = getMode(stack) == OGEVolunteerWatcherEntity.PLAYER_MODE
                ? "mode.origolds_ia.volunteer_watcher.player"
                : "mode.origolds_ia.volunteer_watcher.mob_target";
        tooltip.add(Component.translatable("tooltip.origolds_ia.volunteer_watcher.mode",
                        Component.translatable(modeKey))
                .withStyle(ChatFormatting.GOLD));
    }
}
