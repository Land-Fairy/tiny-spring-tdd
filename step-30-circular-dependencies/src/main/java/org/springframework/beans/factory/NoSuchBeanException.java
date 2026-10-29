package org.springframework.beans.factory;

/**
 * 容器里找不到 Bean 时抛出。
 *
 * <p>
 * 真实 Spring 的对应物是 {@code NoSuchBeanDefinitionException}——注意它多了一个
 * {@code Definition}：它查的是「图纸」而不是「产品」。本章还没有图纸，容器里直接躺着对象，所以名字短一点才诚实。
 *
 * <p>
 * 这个短名会一直用到第 35 章，不再改回去：第 32 章引入「按类型找到多个」的场景时，新增的兄弟异常叫
 * {@code NoUniqueBeanDefinitionException}，两者并列。保留短名是为了让读者始终记得——第 01 章的容器查的是产品。
 *
 * @see org.springframework.beans.factory.NoSuchBeanDefinitionException
 */
public class NoSuchBeanException extends RuntimeException {

	/** 找不到的那个名字；按类型查找时没有名字，为 {@code null}。 */
	private final String beanName;

	/**
	 * 创建一个「没有结构化名字」的异常——按类型查找走这条路。
	 * @param message 说明是哪个类型找不到
	 */
	public NoSuchBeanException(String message) {
		this(null, message);
	}

	/**
	 * 创建一个带结构化名字的异常。
	 * @param beanName 找不到的那个名字，可能为 {@code null}
	 * @param message 说明是哪个名字找不到
	 */
	public NoSuchBeanException(String beanName, String message) {
		super(message);
		this.beanName = beanName;
	}

	/**
	 * 返回找不到的那个名字。
	 * <p>
	 * 按名字查找时总有值；按类型查找时为 {@code null}——那时问题不是「名字错」，而是「没有这种类型」。
	 * 上层要做「找不到就降级」这种分支时，应当用这个字段而不是去 {@code getMessage()} 里做字符串匹配。 真实 Spring 的对应物
	 * {@code NoSuchBeanDefinitionException} 也有 {@code getBeanName()}。
	 * @return 名字，可能为 {@code null}
	 */
	public String getBeanName() {
		return this.beanName;
	}

}
