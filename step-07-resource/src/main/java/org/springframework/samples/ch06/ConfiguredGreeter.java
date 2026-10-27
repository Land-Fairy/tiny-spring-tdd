package org.springframework.samples.ch06;

import org.springframework.beans.factory.InitializingBean;

/**
 * 需要「属性填充 + 初始化回调」才能用的实现。
 *
 * <p>
 * 它有两个用途：
 * <ol>
 * <li>给 {@code HierarchicalBeanFactory} 的测试当普通 Bean（父子容器回退查的是它）；
 * <li>给 {@code AutowireCapableBeanFactory} 的测试当「外部对象」——{@code applyBeanPropertyValues} 与
 * {@code initializeBean} 都是对着一个<b>不是容器造出来的</b>对象干活。
 * </ol>
 *
 * <p>
 * {@code afterPropertiesSet} 里那句判空是刻意的：它把「有没有被初始化过」变成可观察的（抛异常）。
 */
public class ConfiguredGreeter implements Greeter, InitializingBean {

	private String prefix;

	private boolean initialized;

	/**
	 * 设置前缀。
	 * @param prefix 打招呼前缀
	 */
	public void setPrefix(String prefix) {
		this.prefix = prefix;
	}

	/**
	 * 前缀当前的值，测试用来观察属性填充是否发生。
	 * @return 前缀，未设置时为 {@code null}
	 */
	public String getPrefix() {
		return this.prefix;
	}

	/**
	 * 初始化回调是否跑过。
	 * @return 跑过则为 {@code true}
	 */
	public boolean isInitialized() {
		return this.initialized;
	}

	@Override
	public void afterPropertiesSet() {
		if (this.prefix == null) {
			throw new IllegalStateException("prefix must be set before use");
		}
		this.initialized = true;
	}

	@Override
	public String greet(String name) {
		return this.prefix + name;
	}

}
