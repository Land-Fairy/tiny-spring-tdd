package org.springframework.aop;

import org.springframework.aop.framework.AdvisedSupport;

/**
 * 「怎么拿到目标对象」这件事本身，被抽成了一个接口。
 * <p>
 * 第 26 章之前，{@link AdvisedSupport} 直接抱着一根 {@code Object target} 引用。那等于替用户做了一个
 * 承诺：<b>目标永远是这一个 对象，且它一定已经存在</b>。这个承诺在很多场景下不成立：
 * <ul>
 * <li><b>懒加载</b>：目标要到第一次调用时才该创建（{@code LazyInitTargetSource}）；</li>
 * <li><b>热替换</b>：运行中整体换掉目标（{@code HotSwappableTargetSource}）；</li>
 * <li><b>线程隔离</b>：每个线程用自己的目标（{@code ThreadLocalTargetSource}）；</li>
 * <li><b>池化</b>：每次调用从池里借一个目标，用完还回去（{@code CommonsPool2TargetSource}）。</li>
 * </ul>
 * 这些场景的共同点是：目标不是「一个字段」，而是「一段拿取协议」。协议有三个动作 —— 问类型、取、还 —— 于是 接口也只需要三个方法。
 * <p>
 * 真实 Spring 的同名接口还有 {@code isStatic()} 与 {@code isImmutable()} 两个默认方法，用来告诉
 * 调用方「同一个目标能不能被缓存」，本类省略。
 *
 * @param <T> 目标类型
 * @see org.springframework.aop.TargetSource
 */
public interface TargetSource<T> {

	/**
	 * 目标对象的类型。
	 * @return 目标类，不能为 {@code null}
	 */
	Class<?> getTargetClass();

	/**
	 * 取一个目标对象。
	 * <p>
	 * 注意「取」不是「读字段」：这个方法可能创建对象、可能从池里借、可能抛异常。
	 * @return 目标对象
	 * @throws Exception 取不到目标时
	 */
	T getTarget() throws Exception;

	/**
	 * 用完归还。
	 * <p>
	 * 单例目标什么都不用做；池化的目标必须在这里还回去。真实 Spring 把它放在 {@code finally} 里调用， 所以「调用抛异常」也必须归还 ——
	 * 否则池会漏。
	 * @param target 刚才从 {@link #getTarget()} 取到的对象（这里用 {@code Object} 而不是 {@code T}，
	 * 是为了让只关心「还」的调用点不必把泛型擦除的麻烦带出去 —— 真实 Spring 的签名也是 {@code Object}）
	 * @throws Exception 归还失败时
	 */
	void releaseTarget(Object target) throws Exception;

}
