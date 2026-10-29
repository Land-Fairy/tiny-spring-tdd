package org.springframework.aop.framework.adapter;

import java.util.ArrayList;
import java.util.List;

import org.springframework.aop.Advisor;
import org.springframework.aop.Advice;
import org.springframework.aop.MethodInterceptor;

/**
 * 把 Advisor 里的「异构增强」统一成「拦截器」。
 * <p>
 * 这里是本章最反直觉的一处：**链上跑的全是拦截器**。前置增强、后置增强、环绕增强三者在用户眼里是 三种东西，在框架眼里只有一种 ——
 * 因为前两者会被适配器当场翻译成拦截器。这也解释了为什么「环绕增强 能力最大」：它不需要翻译，天生就是拦截器。
 *
 * @see org.springframework.aop.framework.adapter.DefaultAdvisorAdapterRegistry
 */
public class DefaultAdvisorAdapterRegistry {

	private final List<AdvisorAdapter> adapters = new ArrayList<>();

	/**
	 * 创建一个带默认适配器的注册表：认识前置与后置增强。
	 */
	public DefaultAdvisorAdapterRegistry() {
		this.adapters.add(new MethodBeforeAdviceAdapter());
		this.adapters.add(new AfterReturningAdviceAdapter());
	}

	/**
	 * 把一个 Advisor 的增强翻译成拦截器。
	 * @param advisor 顾问
	 * @return 拦截器数组：环绕增强就是它自己，其它增强被适配器包一层
	 * @throws IllegalStateException 遇到不认识的增强类型时
	 */
	public MethodInterceptor[] getInterceptors(Advisor advisor) {
		Advice advice = advisor.getAdvice();
		if (advice instanceof MethodInterceptor methodInterceptor) {
			return new MethodInterceptor[] { methodInterceptor };
		}
		for (AdvisorAdapter adapter : this.adapters) {
			if (adapter.supportsAdvice(advice)) {
				return new MethodInterceptor[] { adapter.getInterceptor(advice) };
			}
		}
		throw new IllegalStateException("Unsupported advice type [" + advice.getClass().getName()
				+ "]: 只有 MethodInterceptor / MethodBeforeAdvice / AfterReturningAdvice 能被适配成拦截器");
	}

}
