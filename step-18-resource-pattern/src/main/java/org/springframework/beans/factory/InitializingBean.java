package org.springframework.beans.factory;

/**
 * 初始化回调：容器在<b>所有属性都填完之后</b>调用一次 {@link #afterPropertiesSet()}。
 *
 * <p>
 * <b>为什么需要它</b>：属性注入有个无法回避的缺点——对象在「构造器返回」到「最后一个 setter 执行完」
 * 之间处于<b>半成品状态</b>。如果某个校验必须等依赖齐了才能做，构造器里做不了，只能等容器通知。 这个接口就是那个通知。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.beans.factory.InitializingBean}（40 行上下），
 * 与 {@code DisposableBean} 成对。真实 Spring 的初始化阶段比这复杂得多：{@code @PostConstruct}、
 * {@code init-method}、{@code BeanPostProcessor#postProcessBeforeInitialization}
 * 都在这一小段时间里发生。 本章只做最小钩子，第 13 章补齐全部。
 *
 * @see org.springframework.beans.factory.InitializingBean
 */
public interface InitializingBean {

	/**
	 * 所有属性注入完成后被调用一次。
	 * @throws Exception 回调失败时容器会把它包成 {@code BeanCreationException}
	 */
	void afterPropertiesSet() throws Exception;

}
