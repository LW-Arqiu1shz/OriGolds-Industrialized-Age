package cn.origolds.ia;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 金源坷垃机（Origold-KeLa Machine）方块。
 *
 * <p>外观是普通立方体：顶面 {@code output_top}、底面 {@code top_bottom}，四个侧面
 * 随档位在 {@code jinkela_side333 / 555 / 777 / 999} 之间切换。</p>
 *
 * <p>共有四档，默认第 4 档（9×9×9），档位决定两件事（详见
 * {@link OGEKelaMachineBlockEntity}）：</p>
 * <ul>
 *   <li>作用立方体的半径：1 档 3×3×3 …… 4 档 9×9×9，档位倍率（3/5/7/9）正好等于边长；</li>
 *   <li>每轮的消耗：骨粉按档位分别是 3 档 25% 概率消耗 1 个、5 档 1 个、7 档 2 个、
 *       9 档 4 个；OGE 电费只由本轮实际处理的方块数量决定（每个方块 1 OGE），与档位无关。</li>
 * </ul>
 *
 * <p><b>空手</b>按住 Ctrl 再点鼠标即可改档：Ctrl+左键降档、Ctrl+右键升档。
 * Ctrl 不是原版交互包会携带的修饰键，按键判定在客户端完成（见
 * {@code OGEKelaMachineClient}），服务端收到 {@link OGEKelaMachineTierPayload}
 * 后由 {@link #adjustTier} 重新校验空手、方块类型与距离再改档。</p>
 */
public final class OGEKelaMachineBlock extends Block implements EntityBlock {

    /** 允许远程改档的最大距离平方（8 格）。 */
    private static final double MAX_ADJUST_DISTANCE_SQ = 64.0;

    /** 档位数量：3×3×3 / 5×5×5 / 7×7×7 / 9×9×9 共四档。 */
    public static final int TIER_COUNT = 4;

    /** 放下时的默认档位下标：第 4 档（9×9×9）。 */
    public static final int DEFAULT_MODE = TIER_COUNT - 1;

    /** 各档作用立方体的半径：1 档 3×3×3 …… 4 档 9×9×9。 */
    private static final int[] TIER_RADIUS = {1, 2, 3, 4};

    /**
     * 各档的倍率：3 / 5 / 7 / 9，正好等于对应档位的边长。
     * 只用于档位展示，不再决定骨粉消耗（消耗量见 {@link #TIER_BONE_MEAL_COST}）。
     */
    private static final int[] TIER_MULTIPLIER = {3, 5, 7, 9};

    /**
     * 各档每轮真正扣除的骨粉数量：0 → 1（3 档）、1 → 1（5 档）、2 → 2（7 档）、3 → 4（9 档）。
     *
     * <p>3 档要按 {@link #TIER_BONE_MEAL_CHANCE} 的概率才真的扣这一次，所以它的
     * 实际期望消耗是 0.25 个/轮；其余档每轮必扣。</p>
     */
    private static final int[] TIER_BONE_MEAL_COST = {1, 1, 2, 4};

    /** 各档每轮真正扣除骨粉的概率：0 → 25%（3 档），其余档 → 100%。 */
    private static final float[] TIER_BONE_MEAL_CHANCE = {0.25F, 1.0F, 1.0F, 1.0F};

    /** 档位方块状态：0/1/2/3 分别代表 3×3×3 / 5×5×5 / 7×7×7 / 9×9×9。 */
    public static final IntegerProperty MODE = IntegerProperty.create("mode", 0, TIER_COUNT - 1);

    public OGEKelaMachineBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(MODE, DEFAULT_MODE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(MODE);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return simpleCodec(OGEKelaMachineBlock::new);
    }

    /** 放下时固定为第 4 档，不跟随玩家朝向（四个侧面按档位切换，没有正反面之别）。 */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState();
    }

    /** 把档位下标夹到合法范围。 */
    public static int clampMode(int mode) {
        return Math.max(0, Math.min(TIER_COUNT - 1, mode));
    }

    /** 档位下标 → 作用立方体半径：0 → 1（3×3×3）…… 3 → 4（9×9×9）。 */
    public static int radiusForMode(int mode) {
        return TIER_RADIUS[clampMode(mode)];
    }

    /** 档位下标 → 覆盖倍率：0 → 3 …… 3 → 9（等于该档边长）。 */
    public static int multiplierForMode(int mode) {
        return TIER_MULTIPLIER[clampMode(mode)];
    }

    /**
     * 档位下标 → 每轮骨粉消耗上限：0 → 1、1 → 1、2 → 2、3 → 4。
     *
     * <p>3 档只有 {@link #boneMealChanceForMode} 的概率真的扣这一次，期望消耗 0.25 个/轮。</p>
     */
    public static int boneMealCostForMode(int mode) {
        return TIER_BONE_MEAL_COST[clampMode(mode)];
    }

    /** 档位下标 → 每轮真正扣除骨粉的概率：0 → 0.25（3 档），其余档 → 1.0。 */
    public static float boneMealChanceForMode(int mode) {
        return TIER_BONE_MEAL_CHANCE[clampMode(mode)];
    }

    /** 档位下标 → 作用范围文字标签，例如 3 → {@code 9x9x9}。 */
    public static String rangeLabel(int mode) {
        int side = radiusForMode(mode) * 2 + 1;
        return side + "x" + side + "x" + side;
    }

    /**
     * 服务端侧调整档位，由客户端 Ctrl+左键/右键的报文触发
     * （见 {@link OGEKelaMachineTierPayload}）。
     *
     * <p>客户端只负责判定 Ctrl（服务端读不到键盘），真正生效前这里把所有条件重新校验一遍：
     * 手上必须空着、必须真的指着金源坷垃机、且在 8 格以内。档位到两端时夹住不变，
     * 但仍播报一次当前档位，让玩家知道已经到底。</p>
     *
     * @param player    发起操作的玩家
     * @param packedPos 目标方块坐标（{@link BlockPos#asLong()} 打包）
     * @param delta     档位变化量：+1 升档、-1 降档
     */
    public static void adjustTier(ServerPlayer player, long packedPos, int delta) {
        if (delta == 0) {
            return;
        }
        BlockPos pos = BlockPos.of(packedPos);
        Level level = player.level();
        if (!level.isLoaded(pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof OGEKelaMachineBlock)) {
            return;
        }
        // “空手”只认主手；主手有物品时 Ctrl+左/右键本来就该交给物品自己处理。
        if (!player.getMainHandItem().isEmpty()) {
            return;
        }
        if (player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_ADJUST_DISTANCE_SQ) {
            return;
        }
        int current = state.getValue(MODE);
        int next = clampMode(current + delta);
        if (next != current) {
            level.setBlock(pos, state.setValue(MODE, next), Block.UPDATE_ALL);
        }
        player.displayClientMessage(Component.translatable("message.origolds_ia.kela_machine_tier",
                next + 1, rangeLabel(next), boneMealCostText(next)), true);
    }

    /**
     * 本档骨粉消耗的展示文本。
     *
     * <p>必然消耗的档位直接显示数量（“消耗 2 个”）；3 档是概率消耗，显示成
     * “25% 概率消耗 1 个”，让玩家一眼看出它不一定每轮都吃粉。</p>
     */
    private static Component boneMealCostText(int mode) {
        int amount = boneMealCostForMode(mode);
        float chance = boneMealChanceForMode(mode);
        if (chance >= 1.0F) {
            return Component.translatable("message.origolds_ia.kela_machine_cost_exact", amount);
        }
        return Component.translatable("message.origolds_ia.kela_machine_cost_chance",
                Math.round(chance * 100.0F), amount);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OGEKelaMachineBlockEntity(pos, state);
    }

    /** 只在服务端驱动：扣电、扣骨粉、周期催熟。 */
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide || type != ModBlockEntities.OGE_KELA_MACHINE.get()) {
            return null;
        }
        return (l, p, s, be) -> {
            if (be instanceof OGEKelaMachineBlockEntity machine) {
                machine.tick();
            }
        };
    }

    /** 该机器没有界面，空手右键不做任何事（保持默认 PASS）。 */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        return InteractionResult.PASS;
    }
}
