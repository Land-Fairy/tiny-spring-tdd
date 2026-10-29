package org.springframework.beans.factory;

/**
 * 用 {@code &} 前缀去要一个工厂，但那个 Bean 根本不是工厂。
 *
 * <p>
 * 真实对应物：{@code org.springframework.beans.factory.BeanIsNotAFactoryException}（它继承
 * {@code BeanNotOfRequiredTypeException}）。本书继承 {@link NoSuchBeanException}，
 * 让调用方只需捕一种「找不到/找错」的异常。
 *
 * @see org.springframework.beans.factory.BeanIsNotAFactoryException
 */
public class BeanIsNotAFactoryException extends NoSuchBeanException {

	private static final long serialVersionUID = 1L;

	/**
	 * 造一个异常。
	 * @param beanName Bean 名字
	 * @param actualType 实际类型
	 */
	public BeanIsNotAFactoryException(String beanName, Class<?> actualType) {
		super("Bean named '" + beanName + "' is expected to be of type '" + FactoryBean.class.getName()
				+ "' but was actually of type '" + actualType.getName() + "'");
	}

}
