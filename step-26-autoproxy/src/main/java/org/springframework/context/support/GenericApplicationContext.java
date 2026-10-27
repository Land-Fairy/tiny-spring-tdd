package org.springframework.context.support;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * 最朴素的上下文：自己持有一个 {@link DefaultListableBeanFactory}，图纸由调用方注册。
 *
 * <p>
 * 它只做两件事：
 * <ol>
 * <li>守住「只能 {@code refresh()} 一次」这条规矩</li>
 * <li>把内部的 BeanFactory 交出去，让调用方注册图纸</li>
 * </ol>
 *
 * <p>
 * 为什么不允许二次 {@code refresh()}？因为这个类的定位是「一次性启动的容器」：
 * 如果允许重来，那么「关闭时销毁了什么、重来时哪些还是旧的」就变成一堆必须回答的问题。 想重新加载配置的场景，正确做法是<b>换一个上下文</b>，而不是复用同一个。 真实
 * Spring 把这个决定写进了异常消息：{@code just call 'refresh' once}。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.context.support.GenericApplicationContext} （636
 * 行）。真实版本的 {@code refreshBeanFactory()} 是 {@code final} 的；本书留成可覆写， 好处是
 * {@link ClassPathXmlApplicationContext} 能直接在它上面挂一行「读 XML」， 而不需要像真实那样再插一层
 * {@code AbstractRefreshableApplicationContext}。
 *
 * @see AbstractApplicationContext
 * @see ClassPathXmlApplicationContext
 */
public class GenericApplicationContext extends AbstractApplicationContext {

	private final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();

	private volatile boolean refreshed;

	/**
	 * 用默认名字造一个上下文。
	 */
	public GenericApplicationContext() {
		this("GenericApplicationContext");
	}

	/**
	 * 用指定名字造一个上下文。名字会出现在「还没 refresh」这类消息里。
	 * @param displayName 显示名
	 */
	public GenericApplicationContext(String displayName) {
		super(displayName);
	}

	@Override
	protected void refreshBeanFactory() {
		if (this.refreshed) {
			throw new IllegalStateException(
					getDisplayName() + " does not support multiple refresh attempts: just call 'refresh' once");
		}
		this.refreshed = true;
	}

	@Override
	public DefaultListableBeanFactory getBeanFactory() {
		return this.beanFactory;
	}

	@Override
	public String toString() {
		return getDisplayName();
	}

}
