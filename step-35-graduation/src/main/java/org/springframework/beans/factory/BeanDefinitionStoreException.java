package org.springframework.beans.factory;

import org.springframework.beans.FatalBeanException;

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
public class BeanDefinitionStoreException extends FatalBeanException {

	public BeanDefinitionStoreException(String message) {
		super(message);
	}

	public BeanDefinitionStoreException(String message, Throwable cause) {
		super(message, cause);
	}

}
