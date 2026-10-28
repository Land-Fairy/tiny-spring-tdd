package org.springframework.aop.framework;

/**
 * 把「当前线程正在执行的代理」暴露出来的工具类。
 * <p>
 * 动机：目标对象的方法体里写 {@code this.other()} 时，{@code this} 是<b>目标</b>而不是代理， 于是这次调用完全绕过拦截器链 ——
 * 这就是「自调用失效」。框架绕不过「方法体里的 this」， 只能反过来问：既然拿不到代理，那我把代理放到一个能被拿到的地方，由你显式去取。
 * <p>
 * 真实 Spring 的同名类 95 行，用的是 {@code NamedThreadLocal}（带名字的 {@code ThreadLocal}， 线程 dump
 * 时能看出是谁）：
 * <ul>
 * <li>{@code :50} {@code private static final ThreadLocal<Object> currentProxy}</li>
 * <li>{@code :66-:73} {@code currentProxy()} —— 取不到就抛 {@code IllegalStateException}，
 * 消息里点明「忘了配置 {@code exposeProxy=true}」和「跨线程」两种常见原因</li>
 * <li>{@code :84-:92} {@code setCurrentProxy} —— <b>包私有</b>，只给框架自己用；
 * 返回旧值，正是为了让调用方能「恢复现场」</li>
 * </ul>
 * 我们的实现保留这三个特征。唯一的偏离：真实 Spring 的字段名是小写的 {@code currentProxy}， 而本书的 checkstyle 对
 * {@code static final} 强制 {@code ConstantName}（全大写下划线）， 所以这里叫
 * {@link #CURRENT_PROXY}。这类「闸门与真实源码冲突」的取舍在第 5 章也出现过一次。
 *
 * @see org.springframework.aop.framework.AopContext
 */
public abstract class AopContext {

	/**
	 * 当前线程上「正在执行的那个代理」。
	 */
	private static final ThreadLocal<Object> CURRENT_PROXY = new ThreadLocal<>();

	/**
	 * 返回当前线程正在执行的代理。
	 * @return 当前代理，永不为 {@code null}
	 * @throws IllegalStateException 没有配置暴露代理，或者在不同线程里调用
	 */
	public static Object currentProxy() throws IllegalStateException {
		Object proxy = CURRENT_PROXY.get();
		if (proxy == null) {
			throw new IllegalStateException(
					"没有可用的代理：请确认开启 exposeProxy，" + "并确保 AopContext.currentProxy() 与 AOP 调用在同一个线程里执行。");
		}
		return proxy;
	}

	/**
	 * 把代理挂到当前线程上，并返回原来的值（供调用方恢复现场）。
	 * <p>
	 * 为什么返回旧值而不是只 {@code set}？因为调用可以嵌套：外层代理执行期间内层代理也要挂上去，
	 * 内层结束后必须把外层的还回来，否则外层剩下的代码会看到内层代理。
	 * @param proxy 要暴露的代理
	 * @return 挂之前的值
	 */
	static Object setCurrentProxy(Object proxy) {
		Object old = CURRENT_PROXY.get();
		if (proxy != null) {
			CURRENT_PROXY.set(proxy);
		}
		else {
			// 显式 remove 而不是 set(null)：长生命周期线程上 set(null) 会留下条目。
			CURRENT_PROXY.remove();
		}
		return old;
	}

}
