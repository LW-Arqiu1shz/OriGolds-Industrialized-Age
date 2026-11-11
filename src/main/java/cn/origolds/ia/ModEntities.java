package cn.origolds.ia;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 本模组的实体类型注册表。
 *
 * <p>两种实体都是“工具型”实体：志愿者观察员（替玩家盯机器状态的人形实体）
 * 与飞旋的金源圆锯片（从锯片台射出的投射实体），都不算敌对生物。</p>
 */
public final class ModEntities {

    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, OriGoldsIndustrializedAge.MOD_ID);

    private static final ResourceKey<EntityType<?>> VOLUNTEER_WATCHER_KEY =
            ResourceKey.create(Registries.ENTITY_TYPE,
                    Identifier.fromNamespaceAndPath(OriGoldsIndustrializedAge.MOD_ID, "volunteer_watcher"));
    private static final ResourceKey<EntityType<?>> SAWBLADE_KEY =
            ResourceKey.create(Registries.ENTITY_TYPE,
                    Identifier.fromNamespaceAndPath(OriGoldsIndustrializedAge.MOD_ID, "sawblade"));

    /** 志愿者观察员：人形小实体，替玩家看机器、随机器报警。 */
    public static final DeferredHolder<EntityType<?>, EntityType<OGEVolunteerWatcherEntity>> VOLUNTEER_WATCHER =
            ENTITY_TYPES.register("volunteer_watcher",
                    () -> EntityType.Builder.<OGEVolunteerWatcherEntity>of(OGEVolunteerWatcherEntity::new, MobCategory.MISC)
                            .sized(0.6F, 1.8F)
                            .eyeHeight(1.62F)
                            .clientTrackingRange(10)
                            .updateInterval(3)
                            .build(VOLUNTEER_WATCHER_KEY));

    /** 金源圆锯片：锯片台射出的旋转投射实体，命中方块后钉在表面持续切割。 */
    public static final DeferredHolder<EntityType<?>, EntityType<OGESawbladeEntity>> SAWBLADE =
            ENTITY_TYPES.register("sawblade",
                    () -> EntityType.Builder.<OGESawbladeEntity>of(OGESawbladeEntity::new, MobCategory.MISC)
                            .sized(1.0F, 1.0F)
                            .clientTrackingRange(10)
                            .updateInterval(20)
                            .noSummon()
                            .build(SAWBLADE_KEY));

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
