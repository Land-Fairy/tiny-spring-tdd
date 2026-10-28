package org.springframework.beans.factory.support;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.NoSuchBeanException;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.core.ParameterNameDiscoverer;

/**
 * 反射实例化的两件事：<b>选哪个构造器</b>，<b>每个参数填什么</b>。
 *
 * <p>
 * <b>为什么需要它</b>：{@code getDeclaredConstructor()} 只会用无参构造器，而没有无参构造器的类是常态。
 * 一个类可能有多个构造器，容器必须回答三个问题：
 * <ol>
 * <li>参数名从哪来 —— {@link ParameterNameDiscoverer}；
 * <li>每个名字对应容器里哪个 Bean —— 按名字向 {@code beanFactory} 要；
 * <li>某个构造器凑不齐参数怎么办 —— <b>咽下异常，换下一个</b>。
 * </ol>
 * 第三点是真实 Spring 里最反直觉的一段：{@code constructors} 按参数个数从多到少排好，逐个尝试， 失败就
 * {@code continue}，全部失败才把收集到的原因一起抛出去。所以「构造器越多越优先」不是猜测， 而是这段 {@code continue} 写出来的顺序。
 *
 * <p>
 * <b>递归发生在这里</b>：为了造 controller 要造 service，为了造 service 要造 repository—— 每一次
 * {@code getBean} 都是一次递归，而递归的终点是「无参构造器」。
 *
 * <p>
 * <b>砍掉了什么</b>：真实 Spring 的 {@code ConstructorResolver} 有 1468 行，本节砍掉的是—— 显式
 * {@code constructor-arg} 参数值（{@code createArgumentArray} 的 {@code resolvedValues} 分支）、
 * 类型转换（第 23 章）、{@code @Autowired} 候选构造器的选择（第 19 章）、 按<b>类型</b>而非名字解析依赖（真实 Spring 走
 * {@code beanFactory.resolveDependency}， 名字只在多个候选中做消歧，见正文 §5）、构造器参数权重与「最接近匹配」评分、工厂方法与
 * {@code @ConstructorProperties}。本章只留「按名字装配 + 参数多的优先」这一条主干。
 *
 * @see ParameterNameDiscoverer
 * @see InstantiationStrategy
 * @see org.springframework.beans.factory.support.ConstructorResolver
 */
public class ConstructorResolver {

	private final BeanFactory beanFactory;

	private final ParameterNameDiscoverer parameterNameDiscoverer;

	private final InstantiationStrategy instantiationStrategy = new SimpleInstantiationStrategy();

	/**
	 * 构造器解析器与它的容器互相持有。
	 *
	 * <p>
	 * 真实 Spring 也是这种双向关系：{@code AbstractAutowireCapableBeanFactory#createBeanInstance} 里
	 * {@code new ConstructorResolver(this)}，而 {@code ConstructorResolver} 又回头调
	 * {@code beanFactory.getBean}。
	 * @param beanFactory 用来查依赖的容器
	 * @param parameterNameDiscoverer 用来问参数名的策略
	 */
	public ConstructorResolver(BeanFactory beanFactory, ParameterNameDiscoverer parameterNameDiscoverer) {
		this.beanFactory = beanFactory;
		this.parameterNameDiscoverer = parameterNameDiscoverer;
	}

	/**
	 * 把 {@code beanClass} 的一个实例造出来。
	 * @param beanName Bean 的名字，只用于错误消息
	 * @param beanClass 要造的类
	 * @return 新对象
	 */
	public Object instantiate(String beanName, Class<?> beanClass) {
		Constructor<?>[] candidates = beanClass.getDeclaredConstructors();
		Arrays.sort(candidates, Comparator.<Constructor<?>>comparingInt(Constructor::getParameterCount).reversed());

		List<UnsatisfiedDependencyException> causes = new ArrayList<>();
		for (Constructor<?> candidate : candidates) {
			if (candidate.isSynthetic()) {
				continue;
			}
			try {
				Object[] args = resolveArguments(beanName, candidate);
				return this.instantiationStrategy.instantiate(candidate, args);
			}
			catch (UnsatisfiedDependencyException ex) {
				// 真实 Spring 的原话就是这一行注释：Swallow and try next constructor.
				causes.add(ex);
			}
		}
		throw buildFailure(beanName, beanClass, causes);
	}

	private Object[] resolveArguments(String beanName, Constructor<?> constructor) {
		Parameter[] parameters = constructor.getParameters();
		if (parameters.length == 0) {
			return new Object[0];
		}
		String[] parameterNames = this.parameterNameDiscoverer.getParameterNames(constructor);
		if (parameterNames == null) {
			throw new UnsatisfiedDependencyException(beanName, "Parameter names of bean '" + beanName
					+ "' are not available for constructor [" + constructor
					+ "]: compile the class with '-parameters' (or register a different ParameterNameDiscoverer)");
		}
		Object[] args = new Object[parameters.length];
		for (int i = 0; i < parameters.length; i++) {
			args[i] = resolveArgument(beanName, i, parameters[i], parameterNames[i]);
		}
		return args;
	}

	private Object resolveArgument(String beanName, int index, Parameter parameter, String parameterName) {
		Class<?> requiredType = parameter.getType();
		Object arg;
		try {
			arg = this.beanFactory.getBean(parameterName);
		}
		catch (NoSuchBeanException ex) {
			throw new UnsatisfiedDependencyException(beanName, dependencyMessage(beanName, index, parameterName)
					+ "No bean named '" + parameterName + "' available; required type [" + requiredType.getName() + "]",
					ex);
		}
		if (!requiredType.isPrimitive() && !requiredType.isInstance(arg)) {
			throw new UnsatisfiedDependencyException(beanName,
					dependencyMessage(beanName, index, parameterName) + "Bean named '" + parameterName
							+ "' is expected to be of type [" + requiredType.getName() + "] but was actually of type ["
							+ arg.getClass().getName() + "]");
		}
		return arg;
	}

	private String dependencyMessage(String beanName, int index, String parameterName) {
		return "Unsatisfied dependency expressed through constructor parameter " + index + " of bean '" + beanName
				+ "' ('" + parameterName + "'): ";
	}

	private UnsatisfiedDependencyException buildFailure(String beanName, Class<?> beanClass,
			List<UnsatisfiedDependencyException> causes) {
		StringBuilder message = new StringBuilder("Could not resolve a constructor for bean '").append(beanName)
			.append("' of class [")
			.append(beanClass.getName())
			.append("]: ")
			.append(causes.size())
			.append(" candidate constructor(s) could not be satisfied");
		for (UnsatisfiedDependencyException cause : causes) {
			message.append("; ").append(cause.getMessage());
		}
		UnsatisfiedDependencyException failure = new UnsatisfiedDependencyException(beanName, message.toString(),
				causes.isEmpty() ? null : causes.get(0));
		// cause 只能挂一条，剩下的线索挂到 relatedCauses——真实 Spring 用它记录「其余候选为什么也不行」。
		for (int i = 1; i < causes.size(); i++) {
			failure.addRelatedCause(causes.get(i));
		}
		return failure;
	}

}
