package cn.origolds.ia;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

/**
 * 掉落函数 {@code origolds_ia:copy_oge_stored}：挖掘会缓存 OGE 的机器时，
 * 把方块实体内的存电量写进掉落物品的 {@code origolds_ia:oge_stored} 数据组件。
 *
 * <p>只要电量为 0（或取不到方块实体）就什么都不做，掉落物保持普通状态；
 * 玩家挖掘、爆炸等一切走战利品表的掉落路径都会保留电量。
 * 手持这样的带电物品放置时由 {@link OGEMachineBlockItem#place} 把电量注回机器。</p>
 */
public final class OGECopyEnergyStoredFunction extends LootItemConditionalFunction {

    public static final MapCodec<OGECopyEnergyStoredFunction> CODEC = RecordCodecBuilder.mapCodec(
            instance -> commonFields(instance).apply(instance, OGECopyEnergyStoredFunction::new));

    private OGECopyEnergyStoredFunction(List<LootItemCondition> conditions) {
        super(conditions);
    }

    @Override
    public MapCodec<OGECopyEnergyStoredFunction> codec() {
        return CODEC;
    }

    @Override
    protected ItemStack run(ItemStack stack, LootContext context) {
        if (stack.isEmpty()) {
            return stack;
        }
        BlockEntity be = context.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        if (be instanceof IOGEEnergyCacheHolder holder) {
            int stored = holder.getOGECachedStorage().getOGEStored();
            if (stored > 0) {
                stack.set(ModDataComponents.OGE_STORED.get(), stored);
            }
        }
        return stack;
    }
}
