package cn.origolds.ia;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.TriState;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock.Action;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock;

/**
 * 金源锯片台的交互与击杀事件。
 *
 * <ul>
 *   <li>潜行 + 右键（命中面任意）锯片台：把手持圆锯片钉到台面相邻面上，
 *   造一个 {@link OGESawbladeEntity}（Y 面朝向取玩家反方向，其余朝北），
 *   消耗 1 点锯片耐久并播放方块放置音效；锯片已用尽时只提示。</li>
 *   <li>潜行 + 左键锯片台：把钉在该面上的锯片实体全部收掉——原版语义就是
 *   "销毁"：每个实体按 {@link OGESawbladeEntity#dropAndDiscard} 掉一块铁锭后消失。</li>
 *   <li>锯片实体造成击杀：若该锯片钉着的锯片台绑了志愿者，击杀经验不落地，
 *   直接收进锯片台储经验（先 {@code skipDropExperience()} 阻断原版掉落）。</li>
 * </ul>
 *
 * <p>26.x 变更：TriState 挪到 {@code net.minecraft.util}；实体创建要传
 * {@link EntitySpawnReason}（这里用 TRIGGERED，机构触发类生成）；
 * action bar 提示走 {@code sendOverlayMessage}。</p>
 */
@EventBusSubscriber(modid = OriGoldsIndustrializedAge.MOD_ID)
public final class OGESawbladeMountEvents {

    private OGESawbladeMountEvents() {
    }

    /** 潜行右键（持圆锯片对锯片台）：钉一片锯片到被击面上。 */
    @SubscribeEvent
    public static void onRightClickBlock(RightClickBlock event) {
        Player player = event.getEntity();
        ItemStack stack = event.getItemStack();
        if (player.isShiftKeyDown() && stack.getItem() instanceof OGECircularSawbladeItem) {
            Level level = event.getLevel();
            BlockPos pos = event.getPos();
            BlockState mount = level.getBlockState(pos);
            if (mount.getBlock() instanceof OGESawbladeMountBlock) {
                Direction face = event.getHitVec().getDirection();
                event.setUseBlock(TriState.FALSE);
                event.setUseItem(TriState.FALSE);
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
                if (!level.isClientSide()) {
                    if (!OGECircularSawbladeItem.isUsable(stack)) {
                        player.sendOverlayMessage(Component.translatable("message.origolds_ia.sawblade_worn"));
                    } else {
                        BlockPos bladePos = pos.relative(face);
                        // 顶/底面：锯片刀面朝玩家背后；墙/柱面：一律朝北。
                        Direction bladeFacing = face.getAxis() == Axis.Y
                                ? player.getDirection().getOpposite() : Direction.NORTH;
                        OGESawbladeEntity blade = ModEntities.SAWBLADE.get()
                                .create(level, EntitySpawnReason.TRIGGERED);
                        if (blade != null) {
                            blade.setPos(bladePos.getX() + 0.5, bladePos.getY(), bladePos.getZ() + 0.5);
                            blade.setMount(pos, face, bladeFacing);
                            blade.setOldPosAndRot();
                            level.addFreshEntity(blade);
                            OGECircularSawbladeItem.consumeOne(stack, player);
                            SoundType soundType = mount.getSoundType();
                            level.playSound(null, bladePos, soundType.getPlaceSound(), SoundSource.BLOCKS,
                                    (soundType.getVolume() + 1.0F) / 2.0F, soundType.getPitch() * 0.8F);
                            player.sendOverlayMessage(Component.translatable("message.origolds_ia.sawblade_installed",
                                    OGECircularSawbladeItem.getDurability(stack)));
                        }
                    }
                }
            }
        }
    }

    /** 潜行左键（持圆锯片对锯片台）：收掉钉在该面上的全部锯片实体。 */
    @SubscribeEvent
    public static void onLeftClickBlock(LeftClickBlock event) {
        if (event.getAction() == Action.START) {
            Player player = event.getEntity();
            if (player.isShiftKeyDown() && event.getItemStack().getItem() instanceof OGECircularSawbladeItem) {
                Level level = event.getLevel();
                BlockPos pos = event.getPos();
                Direction face = event.getFace();
                if (face != null && level.getBlockState(pos).getBlock() instanceof OGESawbladeMountBlock) {
                    event.setUseBlock(TriState.FALSE);
                    event.setUseItem(TriState.FALSE);
                    event.setCanceled(true);
                    if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
                        List<OGESawbladeEntity> blades = OGESawbladeEntity.findMountedOn(serverLevel, pos, face);
                        if (blades.isEmpty()) {
                            player.sendOverlayMessage(Component.translatable("message.origolds_ia.sawblade_none"));
                        } else {
                            for (OGESawbladeEntity blade : blades) {
                                blade.dropAndDiscard(serverLevel);
                            }
                            player.sendOverlayMessage(Component.translatable("message.origolds_ia.sawblade_removed",
                                    blades.size()));
                        }
                    }
                }
            }
        }
    }

    /** 锯片实体击杀：绑了志愿者的锯片台把经验收进内部，而不是原地掉落。 */
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getSource().getDirectEntity() instanceof OGESawbladeEntity blade
                && blade.level() instanceof ServerLevel serverLevel
                && blade.mountBlockEntity() instanceof OGESawbladeMountBlockEntity mount) {
            if (mount.getBoundVolunteer() != null) {
                LivingEntity victim = event.getEntity();
                victim.skipDropExperience();
                mount.collectKillExperience(serverLevel, victim, blade);
            }
        }
    }
}
