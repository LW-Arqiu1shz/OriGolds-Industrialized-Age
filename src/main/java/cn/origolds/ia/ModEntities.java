package cn.origolds.ia;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 本模组实体注册表。 */
public final class ModEntities {

    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, OriGoldsIndustrializedAge.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<OGEVolunteerWatcherEntity>> VOLUNTEER_WATCHER =
            ENTITY_TYPES.register("volunteer_watcher", () -> EntityType.Builder
                    .of(OGEVolunteerWatcherEntity::new, MobCategory.MISC)
                    .sized(0.6F, 1.8F)
                    .eyeHeight(1.62F)
                    .clientTrackingRange(10)
                    .updateInterval(3)
                    .build("volunteer_watcher"));

    /**
     * 装在刃槽上的圆锯刀刃（{@link OGESawbladeEntity}）。
     *
     * <p>它没有碰撞体积、不可攻击也杀不死，只会随着刃槽拆装被生成与移除，
     * 所以 {@code noSummon()} 挡掉 {@code /summon}。尺寸给 1×1：实体位置正好
     * 落在它占的那一格的脚底中心，渲染时模型才能对齐到格子。</p>
     */
    public static final DeferredHolder<EntityType<?>, EntityType<OGESawbladeEntity>> SAWBLADE =
            ENTITY_TYPES.register("sawblade", () -> EntityType.Builder
                    .of(OGESawbladeEntity::new, MobCategory.MISC)
                    .sized(1.0F, 1.0F)
                    .clientTrackingRange(10)
                    .updateInterval(20)
                    .noSummon()
                    .build("sawblade"));

    private ModEntities() {
    }

    public static void register(IEventBus modBus) {
        ENTITY_TYPES.register(modBus);
        modBus.addListener(ModEntities::registerAttributes);
    }

    private static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(VOLUNTEER_WATCHER.get(), OGEVolunteerWatcherEntity.createAttributes().build());
    }
}
