package cn.origolds.ia;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * 虹彩印记叠加层的“技术性方块”。
 *
 * <p>它只被 {@link OGERainbowOverlayRenderer} 拿来当渲染用的方块状态：三个档位各对应
 * 一个 25% 透明度的立方体模型（{@code rainbow_overlay_5x/20x/100x}），由方块实体渲染器
 * 套在贴了印记的机器外面。它没有对应的物品、没有战利品表，也不会进创造页签，
 * 正常玩法拿不到、放不出来。</p>
 *
 * <p>之所以不直接把叠加模型挂到机器方块自己的方块状态上：那样整台机器会被整体挪进
 * 半透明渲染层（原版一个方块状态只有一个 chunk 渲染层），会牵连原有的不透明机身。
 * 走方块实体渲染器则机身照旧、叠加层单独走半透明混合。</p>
 */
public final class OGERainbowOverlayBlock extends Block {

    /** 档位属性：0/1/2 = 5x / 20x / 100x。 */
    public static final IntegerProperty TIER =
            IntegerProperty.create("tier", 0, OGERainbowMarks.TIER_COUNT - 1);

    public OGERainbowOverlayBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(TIER, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(TIER);
    }
}
