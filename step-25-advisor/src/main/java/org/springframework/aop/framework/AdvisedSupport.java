package org.springframework.aop.framework;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.springframework.aop.Advisor;
import org.springframework.aop.MethodInterceptor;
import org.springframework.aop.PointcutAdvisor;
import org.springframework.aop.framework.adapter.DefaultAdvisorAdapterRegistry;

/**
 * 「这个代理对象该被怎么增强」的全部配置：目标 + 一串 Advisor。
 * <p>
 * 它是第 24 章那个「单个 {@code InvocationHandler}」字段的替代品。换成列表之后，两个新问题出现了， 本类各给一个答案：
 * <ol>
 * <li><b>哪些 Advisor 真的对这次调用生效？</b> —— {@link #getInterceptors(Method, Class)} 逐个问切点；</li>
 * <li><b>前置/后置增强怎么混进拦截器链？</b> —— 交给 {@link DefaultAdvisorAdapterRegistry} 翻译。</li>
 * </ol>
 * 真实 Spring 的同名类有 746 行，多出来的部分主要是 `TargetSource`、`introduction`、`exposeProxy`、 以及「advisor
 * 链的缓存」。
 *
 * @see org.springframework.aop.framework.AdvisedSupport
 */
public class AdvisedSupport {

	private Object target;

	private final List<Advisor> advisors = new ArrayList<>();

	private final DefaultAdvisorAdapterRegistry advisorAdapterRegistry = new DefaultAdvisorAdapterRegistry();

	/**
	 * 创建一个空配置。
	 */
	public AdvisedSupport() {
	}

	/**
	 * 创建一个带目标的配置。
	 * @param target 目标对象
	 */
	public AdvisedSupport(Object target) {
		setTarget(target);
	}

	/**
	 * 设置目标对象。
	 * @param target 目标对象，不能为 {@code null}
	 */
	public void setTarget(Object target) {
		if (target == null) {
			throw new IllegalArgumentException("Target object must not be null");
		}
		this.target = target;
	}

	/**
	 * 目标对象。
	 * @return 目标对象
	 */
	public Object getTarget() {
		return this.target;
	}

	/**
	 * 目标类。
	 * @return 目标类；目标尚未设置时为 {@code null}
	 */
	public Class<?> getTargetClass() {
		return (this.target != null ? this.target.getClass() : null);
	}

	/**
	 * 加一个 Advisor。
	 * @param advisor 顾问，不能为 {@code null}
	 */
	public void addAdvisor(Advisor advisor) {
		if (advisor == null) {
			throw new IllegalArgumentException("Advisor must not be null");
		}
		this.advisors.add(advisor);
	}

	/**
	 * 当前的全部 Advisor，按添加顺序。
	 * @return Advisor 列表（可变视图）
	 */
	public List<Advisor> getAdvisors() {
		return this.advisors;
	}

	/**
	 * 算出「这次调用要经过的拦截器链」。
	 * <p>
	 * 三条规则：没有配置时直接返回空链（调用方据此走「不拦」的快速路径）；配置了但不是切点顾问的 （比如「所有方法都增强」的 Advisor）无条件入选；切点顾问要过两道闸
	 * —— 类过滤器、方法匹配器。
	 * @param method 被调用的方法
	 * @param targetClass 目标类
	 * @return 拦截器链（可能是空链，但不为 {@code null}）
	 */
	public List<MethodInterceptor> getInterceptors(Method method, Class<?> targetClass) {
		List<MethodInterceptor> chain = new ArrayList<>();
		if (this.advisors.isEmpty()) {
			return chain;
		}
		for (Advisor advisor : this.advisors) {
			if (advisor instanceof PointcutAdvisor pointcutAdvisor) {
				if (!pointcutAdvisor.getPointcut().getClassFilter().matches(targetClass)) {
					continue;
				}
				if (!pointcutAdvisor.getPointcut().getMethodMatcher().matches(method, targetClass)) {
					continue;
				}
			}
			for (MethodInterceptor interceptor : this.advisorAdapterRegistry.getInterceptors(advisor)) {
				chain.add(interceptor);
			}
		}
		return chain;
	}

}
