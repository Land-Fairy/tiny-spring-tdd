package org.springframework.aop.framework;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import org.springframework.aop.MethodInterceptor;
import org.springframework.aop.MethodInvocation;

/**
 * 「一次方法调用」在链上的载体，也是整条链的驱动器。
 * <p>
 * 链的执行方式很容易被误解成「一个循环依次调用每个拦截器」，其实不是：**每次 {@link #proceed()} 只调一个 拦截器**，而拦截器想继续就必须自己再调一次
 * {@code proceed()}。于是递归代替了循环，也顺便得到了 「拦截器可以不往下走」的能力 —— 前置/后置增强靠的不是特殊语法，而是「谁在什么时候调
 * {@code proceed()}」。
 * <p>
 * 真实 Spring 的同名类 297 行，核心是 {@code :162} 与 {@code :167} 那两行对
 * {@code currentInterceptorIndex} 的推进。
 */
public class ReflectiveMethodInvocation implements MethodInvocation {

	private final Object proxy;

	private final Object target;

	private final Method method;

	private final Object[] arguments;

	private final Class<?> targetClass;

	private final List<? extends MethodInterceptor> interceptors;

	private int currentInterceptorIndex = -1;

	/**
	 * 创建一个调用。
	 * @param proxy 代理对象（{@link #getThis()} 返回它）
	 * @param target 目标对象（链尾真正被反射调用的对象）
	 * @param method 被调用的方法
	 * @param arguments 实参，{@code null} 视作空数组
	 * @param interceptors 这条调用要经过的拦截器链（已按切点过滤并排好序）
	 */
	public ReflectiveMethodInvocation(Object proxy, Object target, Method method, Object[] arguments,
			List<? extends MethodInterceptor> interceptors) {
		this.proxy = proxy;
		this.target = target;
		this.method = method;
		this.arguments = (arguments != null ? arguments : new Object[0]);
		this.targetClass = target.getClass();
		this.interceptors = interceptors;
	}

	@Override
	public Object proceed() throws Throwable {
		if (this.currentInterceptorIndex == this.interceptors.size() - 1) {
			return invokeJoinpoint();
		}
		MethodInterceptor interceptor = this.interceptors.get(++this.currentInterceptorIndex);
		return interceptor.invoke(this);
	}

	/**
	 * 链尾：真正调用目标方法。
	 * @return 目标方法的返回值
	 * @throws Throwable 目标方法抛出的原始异常（已拆掉反射的包装）
	 */
	protected Object invokeJoinpoint() throws Throwable {
		try {
			return this.method.invoke(this.target, this.arguments);
		}
		catch (InvocationTargetException ex) {
			throw ex.getTargetException();
		}
	}

	@Override
	public Method getMethod() {
		return this.method;
	}

	@Override
	public Object[] getArguments() {
		return this.arguments;
	}

	@Override
	public Object getThis() {
		return this.proxy;
	}

	/**
	 * 目标对象 —— 注意它不是 {@link #getThis()}。真实 Spring 没有这个方法（它通过
	 * {@code AopUtils.getTargetClass} 绕路拿），我们加上它是为了让「代理 ≠ 目标」在代码里看得见。
	 * @return 目标对象
	 */
	public Object getTarget() {
		return this.target;
	}

	/**
	 * 目标类。
	 * @return 目标类
	 */
	public Class<?> getTargetClass() {
		return this.targetClass;
	}

	@Override
	public String toString() {
		return this.method.getDeclaringClass().getSimpleName() + "." + this.method.getName() + "()";
	}

}
