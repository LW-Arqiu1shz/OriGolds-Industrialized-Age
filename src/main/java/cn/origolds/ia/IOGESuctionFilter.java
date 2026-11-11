package cn.origolds.ia;

/**
 * “可被漏斗吸取的槽位”过滤器。
 *
 * <p>金源超级漏斗这类按 {@link net.minecraft.world.Container} 逐格遍历的吸取方
 * （见 {@link OGEFaceItems}）会问一遍容器：这一格允许被吸走吗？容器不实现本接口时
 * 一律按“允许”处理；金源圆锯刃槽实现它，只放行废物槽——
 * 武器槽里那把正在用的武器谁都不能隔着漏斗偷走。</p>
 */
public interface IOGESuctionFilter {

    /** 槽位 {@code slot} 里的东西能不能被吸取走。 */
    boolean canSuckSlot(int slot);
}
