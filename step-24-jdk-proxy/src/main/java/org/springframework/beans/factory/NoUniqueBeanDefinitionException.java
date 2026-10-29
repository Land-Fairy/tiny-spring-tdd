package org.springframework.beans.factory;

import java.util.List;

/**
 * 候选不止一个、而且没有任何手段能消歧。
 *
 * <p>
 * <b>为什么它是「好消息」</b>：这个异常在项目里几乎总是让人皱眉，但它比「默默选了其中一个」好一万倍。如果容器在多个候选里随机挑一个， 你的 bug
 * 会表现得像「偶尔用了错误的实现」，排查成本远高于一启动就失败。
 *
 * <p>
 * 继承 {@link NoSuchBeanException} 是为了<b>调用方兼容</b>：既有的 {@code catch (NoSuchBeanException)}
 * 代码不需要改， 同时又能精确处理「有候选但选不出来」这种更具体的情况。真实 Spring 同样是
 * {@code NoUniqueBeanDefinitionException extends NoSuchBeanDefinitionException}——
 * 「一个都没有」与「有多个但选不出」是同一个问题的两个形态：<b>按类型拿不到唯一结果</b>。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.beans.factory.NoUniqueBeanDefinitionException}（69
 * 行）。消息模板与本书一致：{@code No qualifying bean of type '...' available: expected single matching
 * bean but found 2: a, b}。
 *
 * @see org.springframework.beans.factory.NoUniqueBeanDefinitionException
 */
public class NoUniqueBeanDefinitionException extends NoSuchBeanException {

	private final Class<?> type;

	private final List<String> beanNames;

	/**
	 * 用类型与全部候选名构造。
	 * @param type 想要的类型
	 * @param beanNames 找到的候选名（顺序即注册顺序）
	 */
	public NoUniqueBeanDefinitionException(Class<?> type, List<String> beanNames) {
		super("No qualifying bean of type '" + type.getName() + "' available: expected single matching bean but found "
				+ beanNames.size() + ": " + String.join(", ", beanNames));
		this.type = type;
		this.beanNames = List.copyOf(beanNames);
	}

	/**
	 * 想要的类型。
	 * @return 类型
	 */
	public Class<?> getBeanType() {
		return this.type;
	}

	/**
	 * 全部候选名。
	 * @return 候选名列表
	 */
	public List<String> getBeanNames() {
		return this.beanNames;
	}

}
