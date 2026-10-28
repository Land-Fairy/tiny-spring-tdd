package org.springframework.context;

import org.springframework.beans.factory.Aware;
import org.springframework.core.env.Environment;

/**
 * 让 Bean 拿到容器正在用的 {@link Environment}。
 *
 * <p>
 * 这一章才补它，是因为直到要读 profile，容器里才有第一个真的需要环境的<b>基础设施 Bean</b>——
 * {@code ConfigurationClassPostProcessor}。真实 Spring 的它确实实现这个接口，靠第 12 章那套 Aware 回调把环境递进来。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.context.EnvironmentAware}（44 行，就一个方法）。
 *
 * @see ApplicationContextAware
 * @see org.springframework.context.EnvironmentAware
 */
public interface EnvironmentAware extends Aware {

	/**
	 * 注入环境。
	 * @param environment 容器正在用的环境
	 */
	void setEnvironment(Environment environment);

}
