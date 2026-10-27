/*
 * 本书教学代码：tiny-spring-tdd
 */

package org.springframework.aop.framework.autoproxy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.aop.Advisor;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;

/**
 * 最朴素的自动代理创建器：把容器里<b>所有 {@link Advisor} 类型的 Bean</b> 当成候选， 再按目标类过滤。两个方法，一个抽象钩子，这就是第 26
 * 章的全部代码。
 *
 * <p>
 * 真实 Spring 的对应类是 {@code DefaultAdvisorAutoProxyCreator}（几十行），父类
 * {@code AbstractAdvisorAutoProxyCreator} 里做了候选缓存与「本容器 + 父容器」的合并； 再往下的
 * {@code AnnotationAwareAspectJAutoProxyCreator}（153 行）只是把「候选」从 「Advisor Bean」换成了「@Aspect
 * Bean 拆出来的 Advisor」（第 25 章 5.3 节）。
 *
 * <p>
 * 注意 {@link #getAdvicesAndAdvisorsForBean} 的返回值类型是 {@code Object[]} 而不是
 * {@code Advisor[]}：真实 Spring 允许这里同时返回拦截器与 Advisor，因为有些子类（按名字匹配那类）
 * 直接给拦截器。我们保留了同样的签名，代价是调用处多一个 {@code instanceof}。
 */
public class AdvisorAutoProxyCreator extends AbstractAutoProxyCreator {

	@Override
	protected Object[] getAdvicesAndAdvisorsForBean(Class<?> beanClass, String beanName) {
		List<Advisor> candidateAdvisors = findCandidateAdvisors();
		List<Advisor> eligibleAdvisors = AopUtils.findAdvisorsThatCanApply(candidateAdvisors, beanClass);
		return eligibleAdvisors.toArray();
	}

	/**
	 * 候选 = 容器里所有 {@code Advisor} 类型的 Bean。
	 * @return 候选 Advisor（顺序即链的顺序）
	 */
	protected List<Advisor> findCandidateAdvisors() {
		BeanFactory beanFactory = getBeanFactory();
		if (!(beanFactory instanceof ListableBeanFactory listableBeanFactory)) {
			// 真实 Spring 在这里抛 IllegalStateException：没有类型查询能力就无从「自动」。
			throw new IllegalStateException("AdvisorAutoProxyCreator requires a ListableBeanFactory: " + beanFactory);
		}
		Map<String, Advisor> advisors = listableBeanFactory.getBeansOfType(Advisor.class);
		List<Advisor> candidates = new ArrayList<>();
		for (Map.Entry<String, Advisor> entry : advisors.entrySet()) {
			candidates.add(entry.getValue());
		}
		return candidates;
	}

}
