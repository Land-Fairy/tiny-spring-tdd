package org.springframework.context.event;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

/**
 * 多播器的公共部分：<b>两张登记表</b>，以及「名字怎么变成监听器」。
 *
 * <p>
 * 两张表是本章最容易被忽略的设计：
 * <ul>
 * <li>{@code applicationListeners}：<b>对象</b>列表。调用方手里已有监听器实例时走这条。</li>
 * <li>{@code applicationListenerBeans}：<b>名字</b>集合。容器里的监听器 Bean 走这条。</li>
 * </ul>
 *
 * <p>
 * 为什么名字不能当场换成对象？因为 {@code refresh()} 的第十步必须<b>不创建</b>任何 Bean： 那时
 * {@code BeanPostProcessor} 刚注册完，若此刻 {@code getBean} 把某个监听器造出来，
 * 它依赖的对象可能还没走完后处理链——这就是「监听器里 `@Autowired` 拿到半成品」的经典坑。 所以第十步只登记名字，真正的 {@code getBean}
 * 推迟到事件发生时。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.context.event.AbstractApplicationEventMulticaster}
 * （532 行）。真实版多了三样东西：监听器缓存（{@code ListenerCacheKey} + {@code ConcurrentHashMap}）、 基于
 * {@code ResolvableType} 的事件类型预筛、以及 {@code ListenerRetriever} 内部类。
 *
 * @see org.springframework.context.event.AbstractApplicationEventMulticaster
 */
public abstract class AbstractApplicationEventMulticaster implements ApplicationEventMulticaster, BeanFactoryAware {

	private final List<ApplicationListener<?>> applicationListeners = new CopyOnWriteArrayList<>();

	private final Set<String> applicationListenerBeans = Collections.synchronizedSet(new LinkedHashSet<>());

	private BeanFactory beanFactory;

	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	/**
	 * 取内部工厂（子类解析监听器名字时要用）。
	 * @return 工厂
	 */
	protected BeanFactory getBeanFactory() {
		return this.beanFactory;
	}

	@Override
	public void addApplicationListener(ApplicationListener<?> listener) {
		this.applicationListeners.add(listener);
	}

	@Override
	public void addApplicationListenerBean(String listenerBeanName) {
		this.applicationListenerBeans.add(listenerBeanName);
	}

	@Override
	public void removeApplicationListener(ApplicationListener<?> listener) {
		this.applicationListeners.remove(listener);
		this.applicationListenerBeans.removeIf((beanName) -> resolveListener(beanName).equals(listener));
	}

	@Override
	public void removeAllListeners() {
		this.applicationListeners.clear();
		this.applicationListenerBeans.clear();
	}

	/**
	 * 本次事件该投给谁：对象表全要，名字表逐个解析。
	 *
	 * <p>
	 * 注意这里是<b>每次事件都解析一遍</b>——真实 Spring 有缓存（所以它需要 {@code ListenerCacheKey}），
	 * 本书故意不缓存：缓存会让「运行时才注册的监听器收不到事件」这类问题变得难查， 而教学版最重要的是「行为可预测」。
	 * @param event 事件
	 * @return 监听的快照
	 */
	protected Collection<ApplicationListener<?>> getApplicationListeners(ApplicationEvent event) {
		List<ApplicationListener<?>> result = new ArrayList<>(this.applicationListeners);
		for (String beanName : snapshotListenerBeanNames()) {
			result.add(resolveListener(beanName));
		}
		// 第 16 章：同一个比较器，同一条规则。注意排序发生在「算出本次该投给谁」之后、
		// 「真正投递」之前——因为顺序是投递顺序，不是登记顺序。
		AnnotationAwareOrderComparator.sort(result);
		return result;
	}

	private List<String> snapshotListenerBeanNames() {
		synchronized (this.applicationListenerBeans) {
			return new ArrayList<>(this.applicationListenerBeans);
		}
	}

	private ApplicationListener<?> resolveListener(String beanName) {
		if (this.beanFactory == null) {
			throw new IllegalStateException(
					"BeanFactory must be set to retrieve listener beans by name: cannot resolve '" + beanName + "'");
		}
		return this.beanFactory.getBean(beanName, ApplicationListener.class);
	}

}
