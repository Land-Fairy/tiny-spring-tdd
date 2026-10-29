package org.springframework.beans.factory.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;

/**
 * 把一个「需要销毁回调」的 Bean 包装成 {@link DisposableBean}，交给单例池登记。
 *
 * <p>
 * 为什么要适配器？因为一个 Bean 可能需要三种销毁回调（{@code @PreDestroy} / {@code destroy()} /
 * {@code destroy-method}），而单例池只想认一个接口 {@link DisposableBean}。适配器把「三种」统一成「一种」， 顺便记住<b>该
 * Bean 到底有哪几种</b>。
 *
 * <p>
 * <b>本章最重要的一条规矩写在这里</b>：销毁阶段的异常<b>全部吞掉</b>。理由不是偷懒：容器正在关闭， 若第一个 Bean 的销毁抛出去，后面几十个 Bean
 * 就再也销毁不掉了（连接池不归还、临时文件不删）。 真实 Spring 的选择是「记一条 warn
 * 日志，继续下一个」（{@code DisposableBeanAdapter.destroy()} :200-247）。
 * 本书没有日志设施，所以只吞不记——这个「没有日志」的差异在正文里专门讲。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.beans.factory.support.DisposableBeanAdapter} （559
 * 行）。差额主要来自：{@code AutoCloseable.close()} 支持、异步/响应式销毁（{@code Future} /
 * Reactor）、{@code destroy-method} 的 {@code (boolean)} 变体、以及依赖图的级联销毁。
 *
 * @see org.springframework.beans.factory.support.DisposableBeanAdapter
 */
public class DisposableBeanAdapter implements DisposableBean {

	private final Object bean;

	private final String beanName;

	private final boolean invokeDisposableBean;

	private final String destroyMethodName;

	private final List<DestructionAwareBeanPostProcessor> destructionAwareProcessors;

	/**
	 * 造一个适配器。构造期就把「这个 Bean 有哪几种销毁回调」定下来。
	 * @param bean 要销毁的 Bean（可能是被后处理器替换后的代理）
	 * @param beanName Bean 名字，只用于错误消息
	 * @param beanPostProcessors 容器的全部后处理器（里面挑出关心销毁的那些）
	 * @param destroyMethodName 图纸上声明的 {@code destroy-method}，没有则 {@code null}
	 */
	public DisposableBeanAdapter(Object bean, String beanName, List<BeanPostProcessor> beanPostProcessors,
			String destroyMethodName) {
		this.bean = bean;
		this.beanName = beanName;
		this.invokeDisposableBean = (bean instanceof DisposableBean);
		this.destroyMethodName = destroyMethodName;
		this.destructionAwareProcessors = new ArrayList<>();
		for (BeanPostProcessor processor : beanPostProcessors) {
			if (processor instanceof DestructionAwareBeanPostProcessor destructionAware
					&& destructionAware.requiresDestruction(bean, beanName)) {
				this.destructionAwareProcessors.add(destructionAware);
			}
		}
	}

	/**
	 * 这个 Bean 需要挂销毁钩子吗？
	 *
	 * <p>
	 * 三选一：实现了 {@link DisposableBean}、图纸上写了 {@code destroy-method}、或有后处理器声称要参与。 三者都没有的
	 * Bean 不进销毁表——这就是「容器只管它管过的东西」的落地。
	 * @param bean 创建好的 Bean
	 * @param beanName Bean 名字
	 * @param beanPostProcessors 容器的全部后处理器
	 * @param destroyMethodName 图纸上声明的 {@code destroy-method}
	 * @return 需要则 true
	 */
	public static boolean requiresDestruction(Object bean, String beanName, List<BeanPostProcessor> beanPostProcessors,
			String destroyMethodName) {
		if (bean instanceof DisposableBean || destroyMethodName != null) {
			return true;
		}
		for (BeanPostProcessor processor : beanPostProcessors) {
			if (processor instanceof DestructionAwareBeanPostProcessor destructionAware
					&& destructionAware.requiresDestruction(bean, beanName)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 依次执行三种销毁回调：后处理器（{@code @PreDestroy}）→ {@code destroy()} → {@code destroy-method}。
	 *
	 * <p>
	 * 与初始化<b>同向</b>，不是倒过来：初始化是「框架回调 → 接口 → 用户自定义」 （{@code @PostConstruct} →
	 * {@code afterPropertiesSet} → {@code init-method}）， 销毁也是「框架回调 → 接口 →
	 * 用户自定义」（{@code @PreDestroy} → {@code destroy()} → {@code destroy-method}）。上游 Spring
	 * 同样如此。
	 * <p>
	 * 容易混的是另一件事：「逆序」说的是 <b>Bean 与 Bean 之间</b>——依赖者先销毁、被依赖者后销毁 （见 3.4 节）。同一个 Bean
	 * 内部的三条通道不逆序。
	 */
	@Override
	public void destroy() {
		for (DestructionAwareBeanPostProcessor processor : this.destructionAwareProcessors) {
			try {
				processor.postProcessBeforeDestruction(this.bean, this.beanName);
			}
			catch (Exception ignored) {
				// 吞掉：见类注释。真实 Spring 在这里 log.warn。
			}
		}
		if (this.invokeDisposableBean) {
			try {
				((DisposableBean) this.bean).destroy();
			}
			catch (Exception ignored) {
				// 同上。
			}
		}
		if (this.destroyMethodName != null) {
			invokeDestroyMethod();
		}
	}

	private void invokeDestroyMethod() {
		Method method = findNoArgMethod(this.bean.getClass(), this.destroyMethodName);
		if (method == null) {
			// 销毁期找不到方法只能放过：此时已经没有「回滚」这回事了。
			return;
		}
		try {
			method.setAccessible(true);
			method.invoke(this.bean);
		}
		catch (InvocationTargetException | IllegalAccessException ignored) {
			// 同上。
		}
	}

	private Method findNoArgMethod(Class<?> beanClass, String methodName) {
		for (Class<?> clazz = beanClass; clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
			try {
				return clazz.getDeclaredMethod(methodName);
			}
			catch (NoSuchMethodException ignored) {
				// 继续往父类找
			}
		}
		return null;
	}

}
