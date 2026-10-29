package org.springframework.beans.factory.support;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 「容器正在用工厂方法造某个 Bean」这件事，记在当前线程上，按<b>名字</b>记、并且可以嵌套。
 *
 * <p>
 * <b>为什么需要它</b>：第 21 章给配置类生成了一个子类，子类把 {@code @Bean} 方法改写成「先去容器要，没有才真跑方法体」。
 * 可是容器自己造对象时也走这个方法——它必须<b>真跑</b>，否则「先去容器要」会要到自己，然后无限递归。
 *
 * <p>
 * <b>为什么必须按名字比、而且必须是栈</b>（这是本章第三个真红）：一开始这里只存了「有没有人在造东西」一个布尔量。 于是造 {@code greetingService}
 * 时它方法体里那句 {@code counter()} 也被当成「容器在造东西」而放行到方法体， 结果 full
 * 模式白增强了。<b>要区分的是「谁在造我」，不是「线程上有没有人在造东西」</b>。 而嵌套调用（造 A 的过程中造
 * B）会覆盖外层标记，所以要用栈：进一个压一个，出来弹一个。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code AbstractBeanFactory#prototypesCurrentlyInCreation}
 * （{@code AbstractBeanFactory.java:229}，一个 {@code ThreadLocal<Object>}，要么是单个名字、要么是名字的
 * {@code Set}），配合 {@code ConfigurationClassEnhancer.BeanMethodInterceptor} 里对
 * {@code BeanCurrentlyInCreationException} 的专门处理。
 *
 * @see org.springframework.context.annotation.ConfigurationClassEnhancer.BeanMethodInterceptor
 */
public final class FactoryMethodInProgress {

	private static final ThreadLocal<Deque<String>> CURRENT_BEAN_NAMES = ThreadLocal.withInitial(ArrayDeque::new);

	private FactoryMethodInProgress() {
	}

	/**
	 * 压入「开始用工厂方法造这个 Bean」。
	 * @param beanName Bean 的名字
	 */
	static void begin(String beanName) {
		CURRENT_BEAN_NAMES.get().push(beanName);
	}

	/**
	 * 弹出标记。嵌套调用会在这里恢复外层的名字。
	 */
	static void end() {
		Deque<String> names = CURRENT_BEAN_NAMES.get();
		names.poll();
		if (names.isEmpty()) {
			CURRENT_BEAN_NAMES.remove();
		}
	}

	/**
	 * 当前线程是不是正在用工厂方法造对象。
	 * @return 正在造时返回 {@code true}
	 */
	public static boolean isInProgress() {
		return !CURRENT_BEAN_NAMES.get().isEmpty();
	}

	/**
	 * 当前线程正在造的 Bean 名字（最内层的那个）。
	 * @return Bean 名字，没有正在造的返回 {@code null}
	 */
	public static String currentBeanName() {
		return CURRENT_BEAN_NAMES.get().peek();
	}

	/**
	 * 容器是不是正在造<b>这一个</b> Bean。
	 * @param beanName 要问的 Bean 名字
	 * @return 正在造它时返回 {@code true}
	 */
	public static boolean isCreating(String beanName) {
		return beanName != null && beanName.equals(currentBeanName());
	}

}
