/*
 * 本书教学代码：tiny-spring-tdd
 */

package org.springframework.aop.support;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.aop.Advisor;
import org.springframework.aop.Pointcut;
import org.springframework.aop.PointcutAdvisor;

/**
 * 切点与目标类的<b>静态</b>适用性判断。第 25 章把这段逻辑内联在 {@code AdvisedSupport} 里，
 * 这一章它自己长出来了一个类——因为现在有<b>两个</b>地方要问同一个问题：
 *
 * <ul>
 * <li>链的组装（{@code AdvisedSupport#getInterceptors}）：这个<b>方法</b>要不要拦？</li>
 * <li>代理的创建（{@code AbstractAutoProxyCreator}）：这个<b>类</b>值不值得造代理？</li>
 * </ul>
 *
 * <p>
 * 第二个问题是第一个问题的「粗版本」：只要类里<b>有任意一个方法</b>命中切点，这个类就需要代理； 一个都不命中，连代理都不必造。真实 Spring 的
 * {@code AopUtils}（{@code spring-aop}，390 行）里， 这个「粗版本」在
 * {@code canApply(Pointcut, Class, boolean)} {@code :225}，子类重载在 {@code :239}； 批量过滤在
 * {@code findAdvisorsThatCanApply} {@code :319}。
 */
public abstract class AopUtils {

	private AopUtils() {
	}

	/**
	 * 这个切点<a>有可能</a>匹配目标类的某个方法吗（只到类这一层）。
	 * @param pointcut 切点
	 * @param targetClass 目标类
	 * @return 类级过滤器通过、且至少有一个方法命中时返回 {@code true}
	 */
	public static boolean canApply(Pointcut pointcut, Class<?> targetClass) {
		return canApply(pointcut, targetClass, false);
	}

	/**
	 * {@code hasIntroductions} 参数在真实 Spring 里用于引介：引介会<b>给类加方法</b>，于是「方法是否存在」
	 * 都不能在编译期确定，匹配必须放宽。本书不做引介，参数只为与真实签名对齐。
	 * @param pointcut 切点
	 * @param targetClass 目标类
	 * @param hasIntroductions 是否可能出现引介（本书恒为 {@code false}）
	 * @return 是否可能匹配
	 */
	public static boolean canApply(Pointcut pointcut, Class<?> targetClass, boolean hasIntroductions) {
		if (!pointcut.getClassFilter().matches(targetClass)) {
			return false;
		}
		for (Class<?> clazz : getClassHierarchy(targetClass)) {
			for (java.lang.reflect.Method method : clazz.getDeclaredMethods()) {
				if (pointcut.getMethodMatcher().matches(method, targetClass)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * 这个 Advisor 能用于目标类吗。不带切点的 Advisor（真实 Spring 里是引介）对所有类适用。
	 * @param advisor 候选 Advisor
	 * @param targetClass 目标类
	 * @return 是否适用
	 */
	public static boolean canApply(Advisor advisor, Class<?> targetClass) {
		if (advisor instanceof PointcutAdvisor pointcutAdvisor) {
			return canApply(pointcutAdvisor.getPointcut(), targetClass);
		}
		// 不带切点 = 不设条件 = 处处适用。这不是特例，而是「条件为空」的唯一合理解释。
		return true;
	}

	/**
	 * 批量过滤：从候选里挑出与目标类适用的一批，顺序保持不变（顺序就是链的顺序）。
	 * @param candidateAdvisors 候选 Advisor
	 * @param targetClass 目标类
	 * @return 适用的 Advisor 列表
	 */
	public static List<Advisor> findAdvisorsThatCanApply(List<Advisor> candidateAdvisors, Class<?> targetClass) {
		List<Advisor> eligibleAdvisors = new ArrayList<>();
		for (Advisor candidate : candidateAdvisors) {
			if (canApply(candidate, targetClass)) {
				eligibleAdvisors.add(candidate);
			}
		}
		return eligibleAdvisors;
	}

	/**
	 * 这个对象是 JDK 动态代理吗。判断依据是「类由 {@code Proxy} 生成」，不是「实现了某个标记接口」。
	 * @param object 待判断对象
	 * @return 是代理对象时返回 {@code true}
	 */
	public static boolean isAopProxy(Object object) {
		return (object != null && Proxy.isProxyClass(object.getClass()));
	}

	/**
	 * 目标类 + 所有父类 + 所有接口（递归）。匹配要能看到继承来的方法，但 {@code getMethods()} 只给 public
	 * 方法、还带合成方法，所以这里自己走一遍层次。
	 * @param targetClass 目标类
	 * @return 类层次（子类在前）
	 */
	private static Set<Class<?>> getClassHierarchy(Class<?> targetClass) {
		Set<Class<?>> hierarchy = new LinkedHashSet<>();
		for (Class<?> clazz = targetClass; clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
			hierarchy.add(clazz);
		}
		// 接口可以继续带接口，所以用队列而不是单层循环：新加入的接口还要再看一遍。
		Deque<Class<?>> queue = new ArrayDeque<>(hierarchy);
		while (!queue.isEmpty()) {
			for (Class<?> ifc : queue.poll().getInterfaces()) {
				if (hierarchy.add(ifc)) {
					queue.add(ifc);
				}
			}
		}
		return hierarchy;
	}

}
