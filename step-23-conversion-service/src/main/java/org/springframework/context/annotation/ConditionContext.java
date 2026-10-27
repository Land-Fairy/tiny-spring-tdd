package org.springframework.context.annotation;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;

/**
 * {@link Condition} 能看到的世界——<b>只读</b>。
 *
 * <p>
 * 条件在「图纸还在被收集」的阶段运行，此时单例一个都没造出来。所以它拿到的是 <b>改图纸的能力与读环境的能力</b>，而不是「取 Bean」的能力：条件里调
 * {@code getBean} 是真实 Spring 里的著名反模式（会导致过早实例化，甚至死锁）。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.context.annotation.ConditionContext}（129
 * 行，还有 {@code getRegistry} / {@code getResourceLoader} / {@code getClassLoader}）。
 *
 * @see Condition
 */
public interface ConditionContext {

	/**
	 * 图纸视图（能查、能改图纸，不能造对象）。
	 * @return 工厂
	 */
	ConfigurableListableBeanFactory getBeanFactory();

	/**
	 * 环境视图。
	 * @return 环境
	 */
	Environment getEnvironment();

}
