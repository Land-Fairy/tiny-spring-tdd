package org.springframework.aop;

/**
 * 带切点的 Advisor —— 绝大多数 Advisor 都是这一种。
 * <p>
 * 真实 Spring 里还有 {@code IntroductionAdvisor}（给类"加"接口，不改方法行为），它没有切点，因为
 * "加一个接口"这件事天然作用于整个类。我们只做 {@code PointcutAdvisor}。
 *
 * @see org.springframework.aop.PointcutAdvisor
 */
public interface PointcutAdvisor extends Advisor {

	/**
	 * 这个 Advisor 的切点。
	 * @return 切点
	 */
	Pointcut getPointcut();

}
