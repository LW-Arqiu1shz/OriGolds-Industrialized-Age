package cn.origolds.ia;

import java.util.List;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.Item.Properties;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 志愿者观察员物品：对地面右键放置一个站桩监工实体。
 *
 * <p>放置朝向跟随玩家旋转（45° 量化）；物品组件里存的
 * {@link ModDataComponents#VOLUNTEER_MODE} 与 {@code VOLUNTEER_BINDS} 会恢复到新实体上，
 * 因此收回再放出的志愿者保持模式与绑定列表。</p>
 */
public final class OGEVolunteerWatcherItem extends Item {

    public OGEVolunteerWatcherItem(Properties properties) {
        super(properties);
    }

    /** 物品上存的观察员模式（默认玩家模式）。 */
    public static int getMode(ItemStack stack) {
        Integer mode = stack.get(ModDataComponents.VOLUNTEER_MODE.get());
        return mode != null && mode == 1 ? 1 : 0;
    }

    /**
     * 清洗旧版本的冗余组件：1.0.1 之前收回志愿者时无条件写 {@code volunteer_mode=0}
     * （可能还带空绑定列表），这种"脏"堆叠与刚合成的物品不可堆叠。26.x 没有主分支
     * 1.21.1 的 {@code verifyComponentsAfterLoad} 挂载点，这里用 {@code inventoryTick}
     * 等价处理——脏堆叠进入玩家背包/打开的容器就服务端清洗一次，槽位自动同步到客户端。
     */
    @Override
    public void inventoryTick(ItemStack stack, ServerLevel level, Entity holder, EquipmentSlot slot) {
        Integer mode = stack.get(ModDataComponents.VOLUNTEER_MODE.get());
        if (mode != null && mode == 0) {
            stack.remove(ModDataComponents.VOLUNTEER_MODE.get());
        }
        List<OGEVolunteerBindEntry> binds = stack.get(ModDataComponents.VOLUNTEER_BINDS.get());
        if (binds != null && binds.isEmpty()) {
            stack.remove(ModDataComponents.VOLUNTEER_BINDS.get());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public InteractionResult useOn(UseOnContext context) {
        if (context.getClickedFace() == Direction.DOWN) {
            return InteractionResult.FAIL;
        }
        BlockPlaceContext placeContext = new BlockPlaceContext(context);
        BlockPos pos = placeContext.getClickedPos();
        Vec3 center = Vec3.atBottomCenterOf(pos);
        EntityType<OGEVolunteerWatcherEntity> type = (EntityType<OGEVolunteerWatcherEntity>) ModEntities.VOLUNTEER_WATCHER.get();
        AABB bounds = type.getDimensions().makeBoundingBox(center.x, center.y, center.z);
        if (!context.getLevel().noCollision(null, bounds) || !context.getLevel().getEntities(null, bounds).isEmpty()) {
            return InteractionResult.FAIL;
        }
        boolean client = context.getLevel().isClientSide();
        ItemStack stack = context.getItemInHand();
        if (!client && context.getLevel() instanceof ServerLevel serverLevel) {
            OGEVolunteerWatcherEntity watcher = type.create(serverLevel,
                    EntityType.createDefaultStackConfig(serverLevel, stack, context.getPlayer()),
                    pos, EntitySpawnReason.SPAWN_ITEM_USE, true, true);
            if (watcher == null) {
                return InteractionResult.FAIL;
            }
            // 朝向：玩家旋转角量化到 45° 并翻转成“面对玩家”（位置已由生成决定）
            float rotation = Mth.wrapDegrees(Mth.floor((context.getRotation() - 180.0F + 22.5F) / 45.0F) * 45.0F);
            watcher.setYRot(rotation);
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
        return client ? InteractionResult.CONSUME : InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> tooltip, TooltipFlag flag) {
        OGETooltips.add(tooltip, flag, "tooltip.origolds_ia.volunteer_watcher",
                "tooltip.origolds_ia.ctrl.volunteer_watcher", "tooltip.origolds_ia.bs.volunteer_watcher");
        String modeKey = getMode(stack) == 0
                ? "mode.origolds_ia.volunteer_watcher.player" : "mode.origolds_ia.volunteer_watcher.mob_target";
        tooltip.accept(Component.translatable("tooltip.origolds_ia.volunteer_watcher.mode",
                Component.translatable(modeKey)).withStyle(ChatFormatting.GOLD));
    }
}
