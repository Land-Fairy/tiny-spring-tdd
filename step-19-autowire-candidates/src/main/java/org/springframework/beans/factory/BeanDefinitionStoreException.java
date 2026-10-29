package org.springframework.beans.factory;

/**
 * 图纸层面的错误：读不到、读不懂、写重复。
 *
 * <p>
 * 与 {@link BeanCreationException} 的区别是**发生阶段**： 这个异常发生在「还没开始造对象」的阶段（解析、注册）， 而
 * {@code BeanCreationException} 发生在「正在造这个对象」的阶段。 分开的理由是排查方向完全不同——前者去查配置文件，后者去查那个 Bean
 * 的实现。
 *
 * <p>
 * 真实对应物：{@code org.springframework.beans.factory.BeanDefinitionStoreException}。
 *
 * @see org.springframework.beans.factory.BeanDefinitionStoreException
 */
public class BeanDefinitionStoreException extends RuntimeException {

	/** 出问题的那个 Bean 的名字；XML 级错误没有单个 Bean 可归属时，为 {@code null}。 */
	private final String beanName;

	public BeanDefinitionStoreException(String message) {
		this(null, message);
	}

	public BeanDefinitionStoreException(String message, Throwable cause) {
		this(null, message, cause);
	}

	/**
	 * 带结构化 Bean 名构造——上层不必解析消息就知道是哪个 Bean。
	 * @param beanName 出问题的 Bean 名；没有可归属的 Bean 时为 {@code null}
	 * @param message 说明
	 */
	public BeanDefinitionStoreException(String beanName, String message) {
		super(message);
		this.beanName = beanName;
	}

	/**
	 * 带结构化 Bean 名与原因构造。
	 * @param beanName 出问题的 Bean 名；没有可归属的 Bean 时为 {@code null}
	 * @param message 说明
	 * @param cause 原因
	 */
	public BeanDefinitionStoreException(String beanName, String message, Throwable cause) {
		super(message, cause);
		this.beanName = beanName;
	}

	/**
	 * 出问题的那个 Bean 的名字。
	 * @return Bean 名；XML 级错误没有单个 Bean 可归属时返回 {@code null}
	 */
	public String getBeanName() {
		return this.beanName;
	}

}
