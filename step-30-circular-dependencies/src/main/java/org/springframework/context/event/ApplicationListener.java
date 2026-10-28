package org.springframework.context.event;

import java.util.EventListener;

/**
 * 事件监听器。
 *
 * <p>
 * 泛型参数不是装饰：它决定了「这个监听器认哪种事件」。但注意——<b>类型匹配是运行时做的</b>： 多播器把每个事件投给每个监听器，类型不对的那一次转换会抛
 * {@link ClassCastException} （具体怎么处理见 {@code SimpleApplicationEventMulticaster}）。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.context.event.ApplicationListener}（76
 * 行）。 真实版还有 {@code supportsAsyncExecution()}（6.1 新增）与静态工厂 {@code forPayload(...)}，
 * 并且会在多播前用 {@code ResolvableType} 把事件类型<b>预先筛一遍</b>，所以真实的监听器 通常收不到不属于自己的事件——本书没有那套泛型解析（第
 * 34 章会把它列为能力差距）。
 *
 * @see org.springframework.context.ApplicationListener
 */
@FunctionalInterface
public interface ApplicationListener<E extends ApplicationEvent> extends EventListener {

	/**
	 * 处理一个事件。
	 * @param event 事件对象
	 */
	void onApplicationEvent(E event);

}
