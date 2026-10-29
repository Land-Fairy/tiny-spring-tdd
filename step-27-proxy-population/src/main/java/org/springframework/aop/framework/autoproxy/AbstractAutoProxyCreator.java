/*
 * 本书教学代码：tiny-spring-tdd
 */

package org.springframework.aop.framework.autoproxy;

import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.aop.Advisor;
import org.springframework.aop.Pointcut;
import org.springframework.aop.Advice;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;

/**
 * 自动代理创建器：把「造代理」这件事从<b>手写</b>变成<b>容器的一个阶段</b>。
 *
 * <p>
 * 它没有新增任何「造代理」的能力——{@link ProxyFactory} 第 25 章就写完了。它新增的是<b>时机</b>： 在
 * {@code postProcessAfterInitialization} 阶段，对<b>每一个</b> Bean 问一句 「你有能用上的 Advisor
 * 吗」，有就返回代理，没有就原样返回。
 *
 * <p>
 * 三条边界必须守住，否则容器会自己咬自己：
 * <ol>
 * <li><b>基础设施不代理</b>：{@link Advice}、{@link Pointcut}、{@link Advisor}、
 * {@link BeanPostProcessor} 这几类 Bean 如果也被代理，切点匹配会开始匹配「切面自己」， 代理会套代理，直到栈溢出。真实 Spring 在
 * {@code isInfrastructureClass} 里挡这件事。</li>
 * <li><b>没有匹配就不造</b>：{@code getAdvicesAndAdvisorsForBean} 返回空时必须返回原对象。
 * 「不滥用代理」和「能代理」同样重要——一个不必要的代理会带来：多一层栈帧、 {@code instanceof} 目标类失败、字段注入拿到别的对象（第 27 章）。</li>
 * <li><b>子类只回答「哪些 Advisor」</b>：{@code getAdvicesAndAdvisorsForBean} 是唯一的抽象点。 真实 Spring
 * 靠这一个钩子长出了五个子类（按名字、按注解、按 AspectJ 切面……）。</li>
 * </ol>
 *
 * <p>
 * 真实 Spring：{@code AbstractAutoProxyCreator}（640 行，{@code postProcessAfterInitialization}
 * :248 与 :294 两个出口，{@code wrapIfNecessary} :294 附近）。本书砍掉了 early reference、
 * TargetSource、{@code advisedBeans} 缓存与 {@code exposeProxy}。
 *
 * @see org.springframework.aop.framework.autoproxy.AbstractAutoProxyCreator
 */
public abstract class AbstractAutoProxyCreator implements BeanPostProcessor, BeanFactoryAware, Ordered {

	private BeanFactory beanFactory;

	/** 被代理过的 Bean 名字，只为测试与诊断（真实 Spring 用 {@code advisedBeans} 缓存判断结果）。 */
	private final Set<String> proxiedBeanNames = new LinkedHashSet<>();

	/**
	 * 在 after 阶段排到最后。
	 * <p>
	 * 后处理器按「PriorityOrdered → Ordered → 没有顺序」三批注册，批内按 order 值排序。取
	 * {@link Ordered#LOWEST_PRECEDENCE} 的含义是：<b>所有声明了顺序的后处理器都排在我前面</b>。 于是代理一定在它们之后再套上去
	 * —— 「代理是最外层的外壳」这件事就不必依赖 Bean 的定义顺序。
	 * <p>
	 * 真实 Spring 的 {@code AbstractAutoProxyCreator#getOrder()} 同样返回
	 * {@code LOWEST_PRECEDENCE}。
	 */
	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	/**
	 * 供子类按类型查 Bean（例如「容器里所有 Advisor」）。
	 * @return 可列举 Bean 的工厂
	 */
	protected BeanFactory getBeanFactory() {
		return this.beanFactory;
	}

	/**
	 * 已经被代理过的 Bean 名字（按代理生成的顺序）。
	 * @return 不可变副本
	 */
	public Set<String> getProxiedBeanNames() {
		return Set.copyOf(this.proxiedBeanNames);
	}

	/**
	 * 每个 Bean 初始化完成后都会走这里。这是「织入」真正发生的地方。
	 * @param bean 已初始化完的对象
	 * @param beanName Bean 名字
	 * @return 代理对象，或原对象
	 */
	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		return wrapIfNecessary(bean, beanName);
	}

	/**
	 * 判断要不要包、包成什么、包不上就原样返回。
	 * @param bean 目标对象
	 * @param beanName Bean 名字
	 * @return 最终放进单例池的对象
	 */
	protected Object wrapIfNecessary(Object bean, String beanName) {
		if (isInfrastructureClass(bean.getClass())) {
			return bean;
		}
		Object[] specificInterceptors = getAdvicesAndAdvisorsForBean(bean.getClass(), beanName);
		if (specificInterceptors.length == 0) {
			return bean;
		}
		return createProxy(beanName, specificInterceptors, bean);
	}

	/**
	 * 子类唯一的抽象点：这个类/这个 Bean 能用上哪些 Advisor。
	 * @param beanClass 目标类
	 * @param beanName Bean 名字
	 * @return Advisor 或 Advice 数组，空数组表示「不代理」
	 */
	protected abstract Object[] getAdvicesAndAdvisorsForBean(Class<?> beanClass, String beanName);

	/**
	 * 真造代理。三步：装配 → 加增强 → 取代理。
	 * @param beanName Bean 名字
	 * @param specificInterceptors Advisor 或 Advice
	 * @param target 目标对象
	 * @return 代理对象
	 */
	protected Object createProxy(String beanName, Object[] specificInterceptors, Object target) {
		ProxyFactory proxyFactory = new ProxyFactory(target);
		for (Object interceptorOrAdvisor : specificInterceptors) {
			if (interceptorOrAdvisor instanceof Advisor advisor) {
				proxyFactory.addAdvisor(advisor);
			}
			else {
				proxyFactory.addAdvice((Advice) interceptorOrAdvisor);
			}
		}
		this.proxiedBeanNames.add(beanName);
		return proxyFactory.getProxy();
	}

	/**
	 * 基础设施类不代理。少这一类判断，容器启动时会因为「代理切面自己」而无限嵌套。
	 * @param beanClass 待判断的类
	 * @return 是基础设施类时返回 {@code true}
	 */
	protected boolean isInfrastructureClass(Class<?> beanClass) {
		return (Advice.class.isAssignableFrom(beanClass) || Pointcut.class.isAssignableFrom(beanClass)
				|| Advisor.class.isAssignableFrom(beanClass) || BeanPostProcessor.class.isAssignableFrom(beanClass));
	}

}
