package org.springframework.beans.factory.annotation;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;

/**
 * 识别 {@link PostConstruct} / {@link PreDestroy} 的后处理器。
 *
 * <p>
 * 它一个人干两件事，正对应注解的两头：
 * <ul>
 * <li>初始化：在 {@code postProcessBeforeInitialization} 里反射调用 {@code @PostConstruct} 方法；</li>
 * <li>销毁：在 {@code postProcessBeforeDestruction} 里调用 {@code @PreDestroy} 方法， 并用
 * {@code requiresDestruction} 告诉容器「这个 Bean 得挂销毁钩子」。</li>
 * </ul>
 *
 * <p>
 * <b>为什么要缓存</b>：反射扫方法表是相对贵的操作，而同一个类的每个实例扫描结果都一样。真实 Spring 的
 * {@code lifecycleMetadataCache} 就是这个理由（{@code InitDestroyAnnotationBeanPostProcessor}
 * :88）。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.beans.factory.annotation.InitDestroyAnnotationBeanPostProcessor}
 * （489 行）。它支持自定义注解类型（{@code setInitAnnotationType}），识别 {@code @PostConstruct} /
 * {@code @PreDestroy} 的子类由 {@code CommonAnnotationBeanPostProcessor} 配置。
 *
 * @see org.springframework.beans.factory.annotation.InitDestroyAnnotationBeanPostProcessor
 */
public class InitDestroyAnnotationBeanPostProcessor implements DestructionAwareBeanPostProcessor {

	private final Map<Class<?>, List<Method>> initMethodsCache = new ConcurrentHashMap<>();

	private final Map<Class<?>, List<Method>> destroyMethodsCache = new ConcurrentHashMap<>();

	@Override
	public Object postProcessBeforeInitialization(Object bean, String beanName) {
		for (Method method : initMethods(bean.getClass())) {
			invoke(method, bean, beanName, "@PostConstruct");
		}
		return bean;
	}

	@Override
	public void postProcessBeforeDestruction(Object bean, String beanName) {
		for (Method method : destroyMethods(bean.getClass())) {
			invoke(method, bean, beanName, "@PreDestroy");
		}
	}

	@Override
	public boolean requiresDestruction(Object bean, String beanName) {
		return !destroyMethods(bean.getClass()).isEmpty();
	}

	private List<Method> initMethods(Class<?> beanClass) {
		return this.initMethodsCache.computeIfAbsent(beanClass,
				clazz -> findAnnotatedMethods(clazz, PostConstruct.class));
	}

	private List<Method> destroyMethods(Class<?> beanClass) {
		return this.destroyMethodsCache.computeIfAbsent(beanClass,
				clazz -> findAnnotatedMethods(clazz, PreDestroy.class));
	}

	/**
	 * 沿父类链往上找带指定注解的方法。
	 *
	 * <p>
	 * 三个细节：只收<b>方法</b>（注解的 {@code @Target} 已经限定了）；父类里 private 的方法也要找得到；
	 * 同一个方法被子类覆写时只算一次（本实现按「先找到先加」处理，真实 Spring 用 {@code Method} 集合去重）。
	 * @param beanClass 目标类
	 * @param annotationType 注解类型
	 * @return 带该注解的方法，子类的在前
	 */
	private List<Method> findAnnotatedMethods(Class<?> beanClass, Class<? extends Annotation> annotationType) {
		List<Method> methods = new ArrayList<>();
		for (Class<?> clazz = beanClass; clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
			for (Method method : clazz.getDeclaredMethods()) {
				if (method.isSynthetic() || method.isBridge()) {
					continue;
				}
				if (method.isAnnotationPresent(annotationType) && !containsSameMethod(methods, method)) {
					methods.add(method);
				}
			}
		}
		return methods;
	}

	private boolean containsSameMethod(List<Method> methods, Method candidate) {
		for (Method method : methods) {
			if (method.getName().equals(candidate.getName())
					&& method.getParameterCount() == candidate.getParameterCount()) {
				return true;
			}
		}
		return false;
	}

	private void invoke(Method method, Object bean, String beanName, String annotationName) {
		try {
			method.setAccessible(true);
			method.invoke(bean);
		}
		catch (InvocationTargetException ex) {
			// 保留原始异常：Bean 自己写的问题不该被反射包一层（真实 Spring 也会解包）。
			throw new BeanCreationException(beanName,
					"Invocation of " + annotationName + " method of bean '" + beanName + "' failed",
					ex.getTargetException());
		}
		catch (ReflectiveOperationException ex) {
			throw new BeanCreationException(beanName,
					"Could not invoke " + annotationName + " method of bean '" + beanName + "'", ex);
		}
	}

}
