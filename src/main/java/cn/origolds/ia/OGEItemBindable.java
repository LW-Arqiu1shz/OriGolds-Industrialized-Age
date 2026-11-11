package cn.origolds.ia;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 支持“绑定过滤物品”的方块实体能力接口：玩家手持物品 Alt+中键把该物品设成唯一接受的物品，
 * 空手 Alt+中键清空绑定（交互入口见 {@link OGEItemBind}）。
 *
 * <p>{@link OGEItemBind} 只认这个接口，不再为每台机器写一个分支：以后新增机器，
 * 方块实体实现本接口、方块实现 {@link OGEItemBindableBlock}，就自动接上绑定功能，
 * 绑定／清空的流程与播报一行都不用改。</p>
 *
 * <p>绑定只约束“进来的”物品：机器里原有的内容照常取出、照常往输出端送。
 * 因机器而异的部分只有两处，都用默认实现兜住：虚空抽屉因“只存一种物品”而对新绑定
 * 额外有相容性要求（覆写 {@link #isBindingCompatible(ItemStack)}），引力匣与超级漏斗
 * 随时可绑可清。</p>
 */
public interface OGEItemBindable {

    /** 当前绑定的过滤物品（1 个样本）；未绑定时为空栈。 */
    ItemStack getBound();

    /** 写入绑定过滤物品；空栈表示清空绑定。 */
    void setBound(ItemStack stack);

    /**
     * 绑定前的额外校验，默认一律允许。
     *
     * <p>虚空抽屉覆写为“必须与抽屉里现有物品的种类相容”：抽屉本身限死只存一种物品，
     * 若绑定另一种物品，会出现旧物品放不回、新物品也进不来的死结。</p>
     */
    default boolean isBindingCompatible(ItemStack stack) {
        return true;
    }

    /** 绑定被 {@link #isBindingCompatible(ItemStack)} 拒绝时给玩家的提示。 */
    default Component bindRejectedMessage() {
        return Component.translatable("message.origolds_ia.drawer_bind_not_empty");
    }
}
