package org.springframework.context.annotation;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.FactoryMethodInProgress;

/**
 * 被拦截的 {@code @Bean} 方法调用最终都走到这里。
 *
 * <p>
 * 它只回答一个问题：<b>这一次调用，是「容器正在造这个 Bean 自己」，还是「别人想要这个 Bean」？</b>
 *
 * <ul>
 * <li>别人想要（配置类里写 {@code repository()}）：先问容器有没有这个 Bean，有就直接返回——
 * <b>这就是 full 模式「互相调用返回同一实例」的全部秘密</b>，没有更玄的东西。</li>
 * <li>容器自己在造它（{@code DefaultListableBeanFactory} 正在按图纸调这个工厂方法）：必须<b>真跑</b>方法体，
 * 否则「问容器要」会要到自己，无限递归。</li>
 * </ul>
 *
 * <p>
 * 判断依据是 {@link FactoryMethodInProgress} 这个线程标记，而且必须<b>按 Bean 名字比</b>：
 * 「线程上有东西在被造」不等于「正在造我」。两种情况的方法签名完全一样，区别只在调用栈上。
 *
 * <p>
 * <b>真跑方法体这一步有个坑</b>：不能用反射调 {@code method.invoke(this, args)}，因为那样会再次虚分派回
 * 生成类自己的覆写方法，无限递归。必须用 {@code MethodHandles.findSpecial} 拿到「{@code super.} 语义」的
 * 方法句柄——它绕过覆写，直接执行配置类里写的那段方法体，而 {@code this} 仍然是生成类实例，
 * 于是方法体内部的互相调用继续被拦截。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code ConfigurationClassEnhancer.BeanMethodInterceptor}（{@code :233-287}），
 * 它做的判断更多（{@code FactoryBean} 的 {@code &} 前缀、{@code BeanCurrentlyInCreationException} 的宽松处理、
 * 静态方法直接放行）。我们用线程标记把「谁发起的」这个信息显式化了。
 *
 * @see ConfigurationClassEnhancer
 */
/**
 * <p>
 * <b>为什么它必须是 {@code public}</b>：生成类和配置类在同一个包里（比如
 * {@code org.springframework.samples.ch21}）， 而拦截器在我们自己的包里。包私有的话，生成类里那句
 * {@code invokestatic} 会在<b>第一次调用</b>时抛 {@code IllegalAccessError}——不是编译错，是链接错。真实 Spring
 * 的 {@code BeanMethodInterceptor} 同样 public。
 *
 * @see org.springframework.context.annotation.ConfigurationClassEnhancer.BeanMethodInterceptor
 */
public class ConfigMethodInterceptor {

	private final ConfigurableListableBeanFactory beanFactory;

	private final Class<?> configClass;

	private final Map<String, Method> beanMethods;

	private final Map<String, String> beanNamesByMethod;

	private final Map<String, MethodHandle> methodBodies = new ConcurrentHashMap<>();

	ConfigMethodInterceptor(ConfigurableListableBeanFactory beanFactory, Class<?> configClass,
			Map<String, Method> beanMethods, Map<String, String> beanNamesByMethod) {
		this.beanFactory = beanFactory;
		this.configClass = configClass;
		this.beanMethods = beanMethods;
		this.beanNamesByMethod = beanNamesByMethod;
	}

	/**
	 * 生成类里的方法调用的静态入口——生成类没有字段，只能靠 {@code enhanced.getClass()} 反查拦截器。
	 * @param enhanced 被增强的配置类实例（也就是 {@code this}）
	 * @param methodName 被调用的 {@code @Bean} 方法名
	 * @param args 参数（已装箱）
	 * @return 方法返回值
	 * @throws Throwable 方法体抛出的任何异常都原样透出
	 */
	public static Object invoke(Object enhanced, String methodName, Object[] args) throws Throwable {
		return ConfigurationClassEnhancer.interceptorFor(enhanced.getClass()).doInvoke(enhanced, methodName, args);
	}

	/**
	 * 拦截点。容器发起的调用直接放行到方法体，外部发起的调用先问容器。
	 * @param enhanced 配置类实例
	 * @param methodName 方法名
	 * @param args 参数
	 * @return 方法返回值
	 * @throws Throwable 方法体抛出的任何异常
	 */
	Object doInvoke(Object enhanced, String methodName, Object[] args) throws Throwable {
		String beanName = this.beanNamesByMethod.get(methodName);
		if (beanName == null) {
			// 方法体还在，但图纸没登记（条件为假被跳过）。此时放行方法体会得到一个「没人登记的野对象」——
			// 它跑完了所有初始化逻辑却不是单例、也没人管销毁。真实 Spring 一样会在这里失败，我们说清楚原因。
			throw new BeanDefinitionStoreException("@Bean method '" + methodName + "' of config class ["
					+ this.configClass.getName()
					+ "] was skipped by a condition and has no bean definition; it cannot be called at runtime");
		}
		if (!FactoryMethodInProgress.isCreating(beanName) && this.beanFactory.containsBean(beanName)) {
			return this.beanFactory.getBean(beanName);
		}
		return invokeMethodBody(enhanced, methodName, args);
	}

	/**
	 * 执行配置类自己写的那段方法体，{@code this} 仍是生成类实例。句柄按方法名缓存。
	 *
	 * <p>
	 * 缓存安全的前提是<b>句柄不绑定实例</b>：{@code findSpecial} 返回的句柄第一个参数就是接收者，调用时显式传入。 第一版在这里写了
	 * {@code .bindTo(enhanced)}，于是同一个生成类的第二个对象调用 {@code @Bean} 方法时，
	 * 跑的还是第一个对象的方法体——单例配置类永远看不出来，测试 {@code reusesCachedMethodHandleAcrossInstances} 抓到了它。
	 * @param enhanced 配置类实例
	 * @param methodName 方法名
	 * @param args 参数
	 * @return 方法返回值
	 * @throws Throwable 方法体抛出的任何异常
	 */
	private Object invokeMethodBody(Object enhanced, String methodName, Object[] args) throws Throwable {
		MethodHandle body = this.methodBodies.get(methodName);
		if (body == null) {
			Method method = this.beanMethods.get(methodName);
			MethodType type = MethodType.methodType(method.getReturnType(), method.getParameterTypes());
			// findSpecial(声明类, 名字, 类型, 「谁在调」)：等价于生成类里写 super.method(...)
			// 注意：句柄的第一个参数是「接收者」，不要 bindTo——绑定之后缓存就只能服务第一个实例
			body = MethodHandles.privateLookupIn(enhanced.getClass(), MethodHandles.lookup())
				.findSpecial(this.configClass, methodName, type, enhanced.getClass());
			this.methodBodies.put(methodName, body);
		}
		Object[] callArgs = new Object[args.length + 1];
		callArgs[0] = enhanced;
		System.arraycopy(args, 0, callArgs, 1, args.length);
		return body.invokeWithArguments(callArgs);
	}

}
