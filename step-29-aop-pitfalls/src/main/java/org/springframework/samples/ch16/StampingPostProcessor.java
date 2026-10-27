package org.springframework.samples.ch16;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;

/**
 * 给招呼者套上 {@code [stamp]} 的后处理器。
 *
 * <p>
 * 它和 {@link MarkingPostProcessor} 一样只实现 {@link Ordered}，但 {@code getOrder()} 返回 2。
 * 两个都在同一个组里，所以它们的先后<b>只能靠数值决定</b>——这正是「组内排序」那一段活。
 */
public class StampingPostProcessor implements BeanPostProcessor, Ordered {

	@Override
	public int getOrder() {
		return 2;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		if (bean instanceof Greeter greeter) {
			return new DecoratingGreeter(greeter, "stamp");
		}
		return bean;
	}

}
