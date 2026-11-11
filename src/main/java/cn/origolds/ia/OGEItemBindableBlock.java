package cn.origolds.ia;

/**
 * 支持 Alt+中键绑定过滤物品的方块标记接口。
 *
 * <p>Alt 不是原版交互包会携带的修饰键，所以绑定请求由客户端判定 Alt 后发出
 * （见 {@link OGEItemBindClient}），服务端再复核。服务端复核时按方块实体是否实现
 * {@link OGEItemBindable} 判断；客户端这一侧不方便拿方块实体（方块可能还没加载），
 * 因此改按方块类型判断，方块实现本接口即表示“这台机器可以 Alt+中键绑定”。</p>
 */
public interface OGEItemBindableBlock {
}
