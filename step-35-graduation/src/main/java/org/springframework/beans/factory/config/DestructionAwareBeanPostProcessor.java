package org.springframework.beans.factory.config;

/**
 * 关心「Bean 要走了」的后处理器。
 *
 * <p>
 * 为什么销毁要做成后处理器，而不是像初始化那样写成 Bean 的接口？因为<b>销毁元数据来自注解</b>
 * （{@code @PreDestroy}），而容器本身不该知道注解的存在——它只知道「有人想在销毁前插一手」。
 *
 * <p>
 * 两个方法的分工是本章最巧妙的一处设计：{@link #requiresDestruction} 在<b>创建期</b>被问一次 （决定要不要给这个 Bean
 * 挂销毁适配器），{@link #postProcessBeforeDestruction} 在<b>销毁期</b>被调。 前者是「你要不要参与」，后者是「现在轮到你」。真实
 * Spring 同名接口 87 行。
 */
public interface DestructionAwareBeanPostProcessor extends BeanPostProcessor {

	/**
	 * 销毁某一个 Bean 之前调用。
	 * @param bean 要销毁的 Bean
	 * @param beanName Bean 名字
	 */
	default void postProcessBeforeDestruction(Object bean, String beanName) {
	}

	/**
	 * 这个 Bean 需要销毁回调吗？
	 * @param bean 刚创建好的 Bean
	 * @param beanName Bean 名字
	 * @return 需要则 true（默认 true，表示「凡是我处理的 Bean 都要」）
	 */
	default boolean requiresDestruction(Object bean, String beanName) {
		return true;
	}

}
