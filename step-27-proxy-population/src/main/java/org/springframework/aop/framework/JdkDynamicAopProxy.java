package org.springframework.aop.framework;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import org.springframework.aop.MethodInterceptor;
import org.springframework.aop.TargetSource;
import org.springframework.aop.support.AopProxyUtils;

/**
 * 用 JDK 动态代理实现的 {@link AopProxy}。
 * <p>
 * 第 24 章时它还只有一个「独占的」{@code InvocationHandler} 字段；本章换成了 {@link AdvisedSupport} ——
 * 于是每次调用要现场算出一条拦截器链。两处对比值得记住：
 * <ul>
 * <li>{@link #getProxy()} <b>一行没改</b>（除了取接口的来源从「目标类」变成「配置里的目标类」）；</li>
 * <li>{@link #invoke(Object, Method, Object[])} 整个换掉了：转发逻辑搬进了
 * {@link ReflectiveMethodInvocation}。</li>
 * </ul>
 * 真实 Spring 的同名类 360 行，其 {@code :167} 起的 {@code invoke} 比我们多了
 * {@code equals}/{@code hashCode}/{@code Advised}/{@code exposeProxy} 四段特殊处理。
 */
public class JdkDynamicAopProxy implements AopProxy, InvocationHandler {

	private final AdvisedSupport advised;

	/**
	 * 目标的接口有没有自己重新声明 {@code equals}/{@code hashCode}。声明了就把它们当普通业务方法， 交给拦截器链去处理。
	 */
	private final boolean equalsDefined;

	private final boolean hashCodeDefined;

	/**
	 * 用一份配置创建代理工厂。
	 * @param advised 目标与 Advisor 列表
	 */
	public JdkDynamicAopProxy(AdvisedSupport advised) {
		if (advised == null) {
			throw new IllegalArgumentException("AdvisedSupport must not be null");
		}
		// 这里别再调 advised.getTarget() 试探：对池化/懒加载的目标源来说，「取」是有代价的动作，
		// 取了不还就是漏。构造期只该看配置，不该碰目标。
		if (advised.getTargetSource() == null) {
			throw new IllegalArgumentException("Target object must not be null");
		}
		this.advised = advised;
		boolean equalsDefined = false;
		boolean hashCodeDefined = false;
		for (Class<?> candidate : AopProxyUtils.allInterfaces(advised.getTargetClass())) {
			for (Method method : candidate.getDeclaredMethods()) {
				equalsDefined = equalsDefined || AopProxyUtils.isEqualsMethod(method);
				hashCodeDefined = hashCodeDefined || AopProxyUtils.isHashCodeMethod(method);
			}
		}
		this.equalsDefined = equalsDefined;
		this.hashCodeDefined = hashCodeDefined;
	}

	/**
	 * 只给目标、不增强的便捷构造器。
	 * @param target 目标对象
	 */
	public JdkDynamicAopProxy(Object target) {
		this(new AdvisedSupport(target));
	}

	@Override
	public Object getProxy() {
		Class<?>[] interfaces = AopProxyUtils.allInterfaces(this.advised.getTargetClass());
		if (interfaces.length == 0) {
			// 没有接口就没有代理的「形状」。Proxy 本身允许传空数组，但那样造出来的对象谁都调不动，
			// 所以这里当场拒绝 —— 真实 Spring 也不退让，只是它的退路是换技术：DefaultAopProxyFactory
			// 在没有接口时改用 CGLIB 生成子类。
			throw new IllegalArgumentException(
					"无法为 " + this.advised.getTargetClass().getName() + " 生成 JDK 动态代理：它没有实现任何接口（真实 Spring 会退回 CGLIB）");
		}
		return Proxy.newProxyInstance(classLoader(), interfaces, this);
	}

	private ClassLoader classLoader() {
		ClassLoader classLoader = this.advised.getTargetClass().getClassLoader();
		return (classLoader != null ? classLoader : JdkDynamicAopProxy.class.getClassLoader());
	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		// 先生命契约方法：切点常常把「一切方法」都匹配上，但 equals/hashCode 不能由拦截器随便回一个值。
		// 真实 Spring 在这里调的是 handler 自己的 equals/hashCode（:175-:181，:297-:298），
		// 效果是「同一个目标出来的代理身份相同」；我们只做到「同一个代理身份相同」，够用且好讲。
		if (!this.equalsDefined && AopProxyUtils.isEqualsMethod(method)) {
			return proxy == args[0];
		}
		if (!this.hashCodeDefined && AopProxyUtils.isHashCodeMethod(method)) {
			return System.identityHashCode(proxy);
		}
		// 第 27 章：目标不再是「一根字段」，而是「先取、用完还」的协议。
		TargetSource<?> targetSource = this.advised.getTargetSource();
		Object target = targetSource.getTarget();
		List<MethodInterceptor> chain = this.advised.getInterceptors(method, this.advised.getTargetClass());
		try {
			if (chain.isEmpty()) {
				// 没有 Advisor 命中：不值得为一次调用造一个 ReflectiveMethodInvocation。
				try {
					return method.invoke(target, args);
				}
				catch (InvocationTargetException ex) {
					throw ex.getTargetException();
				}
			}
			return new ReflectiveMethodInvocation(proxy, target, method, args, chain).proceed();
		}
		finally {
			// 必须放在 finally：调用抛异常时目标一样要还，否则池化的目标源会越用越少。
			targetSource.releaseTarget(target);
		}
	}

	/**
	 * 当前配置。
	 * @return 配置
	 */
	public AdvisedSupport getAdvised() {
		return this.advised;
	}

	/**
	 * 目标对象（便捷方法）。
	 * @return 目标对象
	 */
	public Object getTarget() {
		return this.advised.getTarget();
	}

}
