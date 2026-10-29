package org.springframework.beans.factory.support;

import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.core.io.Resource;

/**
 * 「把某个来源里的图纸读出来，塞进注册表」这件事的抽象。
 *
 * <p>
 * 本章只实现 XML 一种来源，但接口按真实结构定：将来的注解扫描器 （{@code ClassPathBeanDefinitionScanner}）与 Groovy
 * 配置读的是同一个接口。
 *
 * <p>
 * 注意这个方法签名里**没有 XML**——它只说「从一个 {@link Resource} 读图纸」。
 * 具体怎么读（XML？properties？YAML？）由实现类决定。这是第 07 章那个抽象的第一次收获： 读取器不必知道资源是 classpath 还是文件还是内存。
 *
 * <p>
 * 真实对应物：{@code org.springframework.beans.factory.support.BeanDefinitionReader}（121 行，5
 * 个方法）。 我们留 3 个，砍掉 {@code getResourceLoader}／{@code getBeanClassLoader}／
 * {@code getBeanNameGenerator} 之外的其余重载。
 *
 * @see org.springframework.beans.factory.support.BeanDefinitionReader
 */
public interface BeanDefinitionReader {

	/**
	 * 图纸最终要塞进的注册表。
	 */
	BeanDefinitionRegistry getRegistry();

	/**
	 * 读取一个资源，返回本次注册的图纸数量。
	 * @throws BeanDefinitionStoreException 读不到、读不懂、写重复
	 */
	int loadBeanDefinitions(Resource resource);

	/**
	 * 读取多个资源。
	 */
	int loadBeanDefinitions(Resource... resources);

	/**
	 * 按位置字符串读取——内部交给 {@code ResourceLoader} 解释这个字符串。
	 */
	int loadBeanDefinitions(String location);

}
