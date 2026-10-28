package org.springframework.samples.ch16;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;

/**
 * 给招呼者套上 {@code [audit]} 的后处理器。
 *
 * <p>
 * 它实现 {@link Ordered}，{@code getOrder()} 返回 1——数值上比 {@link TaggingPostProcessor} 的 999
 * 更小。 但因为它不是 {@code PriorityOrdered}，它<b>反而后跑</b>。
 *
 * <p>
 * 为什么这个例子能暴露「顺序错了就出 bug」？因为 {@code [audit]} 的语义是「审计最终产物」：
 * 它必须包在最外层，才能看到完整的招呼语。一旦它先跑，它审计到的是中间结果—— <b>数值更小、语义更该靠后</b>，这正是排序规则必须区分两组的原因。
 */
public class MarkingPostProcessor implements BeanPostProcessor, Ordered {

	@Override
	public int getOrder() {
		return 1;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		if (bean instanceof Greeter greeter) {
			return new DecoratingGreeter(greeter, "audit");
		}
		return bean;
	}

}
