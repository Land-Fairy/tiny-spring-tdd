package org.springframework.samples.ch14;

import org.springframework.beans.factory.FactoryBean;

/**
 * 一台「造 Greeter 的机器」。
 *
 * <p>
 * 它在容器里是一个 Bean（由容器反射造出来、由容器填 {@code prefix}、受容器生命周期管辖）， 但调用方
 * {@code getBean("greeterFactory")} 拿到的是<b>产品</b>。
 */
public class GreeterFactory implements FactoryBean<Greeter> {

	private String prefix = "";

	private boolean singleton = true;

	private int createdCount;

	/**
	 * 注入前缀（由容器填）。
	 * @param prefix 前缀
	 */
	public void setPrefix(String prefix) {
		this.prefix = prefix;
	}

	/**
	 * 切换：产品是单例还是每次新建。
	 * @param singleton 是否单例
	 */
	public void setSingleton(boolean singleton) {
		this.singleton = singleton;
	}

	/**
	 * 一共造了几个产品（测试用来验证缓存是否生效）。
	 * @return 造过的产品数
	 */
	public int createdCount() {
		return this.createdCount;
	}

	@Override
	public Greeter getObject() {
		this.createdCount++;
		return new PrefixedGreeter(this.prefix);
	}

	@Override
	public Class<?> getObjectType() {
		return Greeter.class;
	}

	@Override
	public boolean isSingleton() {
		return this.singleton;
	}

}
