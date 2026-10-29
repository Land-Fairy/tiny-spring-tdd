package org.springframework.aop.framework;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.springframework.aop.support.AopProxyUtils;

/**
 * 用 JDK 动态代理实现的 {@link AopProxy}。
 * <p>
 * 这个类同时是 {@link InvocationHandler}：{@code Proxy.newProxyInstance} 要的那个「方法调用落到哪里」的
 * 回调，就是它自己。真实 Spring 的同名类（spring-aop，428 行）也是这个形状 —— {@code JdkDynamicAopProxy
 * implements AopProxy, InvocationHandler}。
 * <p>
 * 本章只做「拦截得到」和「转发得回去」两件事；第 25 章会把单个 {@code InvocationHandler} 换成 一条
 * {@code MethodInterceptor} 链，届时 {@link #invoke} 的前几行会整个换掉，而 {@link #getProxy()} 一行不用动。
 *
 * @see org.springframework.aop.framework.JdkDynamicAopProxy
 */
public class JdkDynamicAopProxy implements AopProxy, InvocationHandler {

	private final Object target;

	private final InvocationHandler interceptor;

	/**
	 * 创建一个「只转发、不拦截」的代理。
	 * @param target 被代理的对象
	 */
	public JdkDynamicAopProxy(Object target) {
		this(target, null);
	}

	/**
	 * 创建一个「先交给拦截器、再由拦截器决定要不要转发」的代理。
	 * @param target 被代理的对象
	 * @param interceptor 拦截器；为 {@code null} 表示直接转发
	 */
	public JdkDynamicAopProxy(Object target, InvocationHandler interceptor) {
		if (target == null) {
			throw new IllegalArgumentException("Target object must not be null");
		}
		this.target = target;
		this.interceptor = interceptor;
	}

	@Override
	public Object getProxy() {
		Class<?>[] interfaces = AopProxyUtils.allInterfaces(this.target.getClass());
		if (interfaces.length == 0) {
			// 没有接口就没有代理的「形状」。Proxy 本身允许传空数组，但那样造出来的对象谁都调不动，
			// 所以这里当场拒绝 —— 真实 Spring 也不退让，只是它的退路是换技术：DefaultAopProxyFactory
			// 在没有接口时改用 CGLIB 生成子类。
			throw new IllegalArgumentException(
					"无法为 " + this.target.getClass().getName() + " 生成 JDK 动态代理：它没有实现任何接口（真实 Spring 会退回 CGLIB）");
		}
		return Proxy.newProxyInstance(classLoader(), interfaces, this);
	}

	private ClassLoader classLoader() {
		ClassLoader classLoader = this.target.getClass().getClassLoader();
		return (classLoader != null ? classLoader : JdkDynamicAopProxy.class.getClassLoader());
	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		if (this.interceptor != null) {
			return this.interceptor.invoke(proxy, method, args);
		}
		try {
			return method.invoke(this.target, args);
		}
		catch (InvocationTargetException ex) {
			// 目标方法抛的异常被反射包了一层；不拆开的话，调用方 catch 不到它真正抛的类型。
			throw ex.getTargetException();
		}
	}

	public Object getTarget() {
		return this.target;
	}

}
