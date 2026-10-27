package org.springframework.beans.factory.naive;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.NoSuchBeanException;

/**
 * 第 01 章的「容器」：{@code Map<String, Object>} 外面包一层。
 *
 * <p>
 * 这不是玩具，这是所有 IoC 容器的起点。真实 Spring 的 {@code DefaultListableBeanFactory} 里也有一个
 * {@code Map<String, Object>} 名叫 {@code singletonObjects}——区别只在于它旁边还挂了 11 个辅助结构。本章的任务就是
 * 让你先亲手体验「只有这一个 Map」会在哪三个地方崩掉，从而明白那 11 个结构不是过度设计。
 *
 * <p>
 * <b>本章的容器有三个已知的致命缺陷</b>，均在测试里被显式钉住：
 * <ol>
 * <li>只能存实例，所以同一个名字永远拿到同一个对象——没有多例（第 14 章）
 * <li>按类型查找只返回「第一个匹配项」，顺序还取决于 {@link HashMap} 的迭代顺序（第 06、19 章）
 * <li>不知道依赖关系，属性填充必须由调用方手工完成（第 05、11 章）
 * </ol>
 *
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
	 * 「第一个」是遍历 {@link HashMap} 得到的顺序，与注册顺序无关。这一点会在 {@code HashMapOrderTests}
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
