package org.springframework.context.event;

/**
 * 「我能发事件」的能力接口。
 *
 * <p>
 * 只有一个方法。真实 Spring 有两个重载（{@code publishEvent(ApplicationEvent)} 与
 * {@code publishEvent(Object)}，后者把任意对象包成 {@code PayloadApplicationEvent}），
 * 本书只留强类型那个——因为「随便塞个对象当事件」会让监听器失去类型可读性。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.context.ApplicationEventPublisher}（90
 * 行）。
 *
 * @see org.springframework.context.ApplicationEventPublisher
 */
@FunctionalInterface
public interface ApplicationEventPublisher {

	/**
	 * 发布一个事件。
	 * @param event 事件对象
	 */
	void publishEvent(ApplicationEvent event);

}
