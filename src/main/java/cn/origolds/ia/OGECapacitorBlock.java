package cn.origolds.ia;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * 金源能储电池方块。
 *
 * <p>在普通机器方块的基础上增加 {@link #ENERGY_LEVEL} 方块状态属性（0~14）：
 * 每 {@link #ENERGY_STEP} 单位 OGE 为一档，服务端在档位变化时改写方块状态，
 * 客户端据此选用对应的方块模型——箱内“电量液面”高度随存电量上升。
 * 全部显示走原版方块模型管线（1px 厚的上下金属底板 + 四条 1×1 棱柱立柱
 * 构成框架，四侧为纯白 50% 半透明玻璃面板（translucent）+ 全封闭不透明
 * 内腔 + 内置液面方块），因此与光影（Iris/Oculus 等）天然兼容，无需自
 * 定义渲染器；框架与内腔均为实心像素，杜绝“看穿方块”的穿模问题。
 * 物品栏则使用独立的简洁立方体模型（cutout 不透明渲染），像普通方块一样
 * 拿在手上，不展示内部建模。</p>
 */
public final class OGECapacitorBlock extends OGEMachineBlock {

    /** 每个档位代表的 OGE 数量：贴图 0/4000/…/56000 共 15 档。 */
    public static final int ENERGY_STEP = 4000;

    /** 最高档位（对应 56000.png；充满 60000 也显示这一档）。 */
    public static final int MAX_LEVEL = 14;

    /** 电量档位属性：stored / 4000，封顶 {@link #MAX_LEVEL}。 */
    public static final IntegerProperty ENERGY_LEVEL =
            IntegerProperty.create("energy_level", 0, MAX_LEVEL);

    public OGECapacitorBlock(int producePerSecond, int capacity, BlockBehaviour.Properties properties) {
        super(producePerSecond, capacity, OGEMachineKind.CAPACITOR, properties);
        registerDefaultState(stateDefinition.any().setValue(ENERGY_LEVEL, 0));
    }

    /** 由存储量换算当前应有的档位。 */
    public static int levelFor(int stored) {
        if (stored < 0) {
            stored = 0;
        }
        return Math.min(MAX_LEVEL, stored / ENERGY_STEP);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(ENERGY_LEVEL);
    }
}
