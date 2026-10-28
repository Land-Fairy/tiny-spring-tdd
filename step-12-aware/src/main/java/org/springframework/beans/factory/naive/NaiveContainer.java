package org.springframework.beans.factory.naive;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.NoSuchBeanException;

/**
 * 第 01 章的「容器」：{@code Map<String, Object>} 外面包一层。
 *
 * <p>
 * 这不是玩具，这是所有 IoC 容器的起点。真实 Spring 的 {@code DefaultSingletonBeanRegistry} 里也有一个
 * {@code Map<String, Object>} 名叫 {@code singletonObjects}——区别在于它旁边还挂着 18 个实例字段：另外 3
 * 张单例登记表、 4 个「创建中」集合、2 把锁、销毁回调表、3 张依赖关系表。本章的任务就是让你先亲手体验「只有这一个 Map」
 * 会在哪三个地方崩掉，从而明白那些结构不是过度设计。
 *
 * <p>
 * <b>本章的容器有三个已知的致命缺陷</b>，均在测试里被显式钉住：
 * <ol>
 * <li>只能存实例，所以同一个名字永远拿到同一个对象——没有多例（第 14 章）
 * <li>按类型查找只返回「第一个匹配项」，顺序还取决于 {@link HashMap} 的迭代顺序（第 06、19 章）
 * <li>不知道依赖关系，属性填充必须由调用方手工完成（第 05、11 章）
 * </ol>
 *
 * <p>
 *
 * @implNote 教学用反面示例：故意保留朴素写法，不要照抄进生产代码（见 docs/adr/0006）。
 * <p>
 * 非线程安全：{@code HashMap} 没有同步，{@code register} 与 {@code getBean} 之间也没有 happens-before，
 * 并发调用会读到半初始化的对象，或者让 {@code getBean(Class)} 的遍历抛
 * {@code ConcurrentModificationException}。真实 Spring 用 {@code ConcurrentHashMap} 加锁解决，那是第
 * 03 章的事。
 * @see org.springframework.beans.factory.support.DefaultListableBeanFactory
 */
public class NaiveContainer {

	/** 名字 → 实例。真实 Spring 里这个字段叫 singletonObjects。 */
	private final Map<String, Object> beans = new HashMap<>();

	/**
	 * 把一个已经 new 好的对象按名字登记进来。
	 * @param name Bean 的名字
	 * @param bean 已经创建好的实例
	 */
	public void register(String name, Object bean) {
		this.beans.put(name, bean);
	}

	/**
	 * 按名字取出对象。
	 * @param name Bean 的名字
	 * @return 登记时放进去的那个实例
	 * @throws NoSuchBeanException 名字不存在时
	 */
	public Object getBean(String name) {
		Object bean = this.beans.get(name);
		if (bean == null) {
			throw new NoSuchBeanException("No bean named '" + name + "' available");
		}
		return bean;
	}

	/**
	 * 按类型取出对象——朴素实现，返回第一个匹配项。
	 *
	 * <p>
	 * 「第一个」是遍历 {@link HashMap} 得到的顺序，与注册顺序无关。这一点在 {@code GetByTypeTests}
	 * 里被钉住，它是本章第二个失败场景的根因。
	 * @param <T> 期望类型
	 * @param requiredType 期望类型
	 * @return 第一个 {@code isInstance} 成立的实例
	 * @throws NoSuchBeanException 没有任何实例匹配时
	 */
	public <T> T getBean(Class<T> requiredType) {
		for (Object candidate : this.beans.values()) {
			if (requiredType.isInstance(candidate)) {
				return requiredType.cast(candidate);
			}
		}
		throw new NoSuchBeanException("No qualifying bean of type '" + requiredType.getName() + "' available");
	}

	/**
	 * 名字是否已登记。
	 * @param name Bean 的名字
	 * @return 已登记返回 {@code true}
	 */
	public boolean containsBean(String name) {
		return this.beans.containsKey(name);
	}

	/**
	 * 已登记的 Bean 个数。
	 * @return 个数
	 */
	public int size() {
		return this.beans.size();
	}

}
