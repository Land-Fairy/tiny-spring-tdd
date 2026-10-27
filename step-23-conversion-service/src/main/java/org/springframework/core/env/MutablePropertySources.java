package org.springframework.core.env;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 可修改的 {@link PropertySources} 实现：一条 {@link CopyOnWriteArrayList}。
 *
 * <p>
 * 「前面的赢」这条规则，全部实现在这里：{@link #addFirst} 把来源插到链首（优先级最高），{@link #addLast}
 * 追加到链尾（优先级最低）。{@link StandardEnvironment#getProperty} 只是按这条链的顺序逐个问一遍。
 *
 * <p>
 * <b>为什么用 {@code CopyOnWriteArrayList}</b>：属性来源在启动期被插入、在运行期被反复读取。读多写极少，正是写时复制的场景。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.core.env.MutablePropertySources}（232 行），
 * 字段是
 * {@code private final List<PropertySource<?>> propertySourceList = new CopyOnWriteArrayList<>()}
 * （:43）——连这个选择都一样。
 *
 * @see PropertySources
 */
public class MutablePropertySources implements PropertySources {

	private final List<PropertySource<?>> propertySourceList = new CopyOnWriteArrayList<>();

	@Override
	public boolean contains(String name) {
		return get(name) != null;
	}

	@Override
	public PropertySource<?> get(String name) {
		for (PropertySource<?> propertySource : this.propertySourceList) {
			if (propertySource.getName().equals(name)) {
				return propertySource;
			}
		}
		return null;
	}

	/**
	 * 插到链首：优先级最高。
	 * @param propertySource 要加入的来源
	 */
	public void addFirst(PropertySource<?> propertySource) {
		this.propertySourceList.add(0, propertySource);
	}

	/**
	 * 追加到链尾：优先级最低。
	 * @param propertySource 要加入的来源
	 */
	public void addLast(PropertySource<?> propertySource) {
		this.propertySourceList.add(propertySource);
	}

	@Override
	public Iterator<PropertySource<?>> iterator() {
		return this.propertySourceList.iterator();
	}

}
