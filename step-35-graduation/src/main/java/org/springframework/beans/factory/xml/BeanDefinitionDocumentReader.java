package org.springframework.beans.factory.xml;

import org.w3c.dom.Document;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;

/**
 * 「一棵已经解析好的 DOM 树 → 一批图纸」这件事的抽象。
 *
 * <p>
 * 为什么这一步要单独一个接口？因为它与「XML 怎么变成 DOM」完全无关： 真实 Spring 里同一层还被用来处理
 * {@code <beans profile="...">}、 自定义命名空间标签、{@code <import>} 递归——那些都不关心字节是怎么读进来的。
 *
 * <p>
 * 真实对应物：{@code org.springframework.beans.factory.xml.BeanDefinitionDocumentReader}（50 行）。
 * 真实签名是 {@code void registerBeanDefinitions(Document, XmlReaderContext)}， 上下文对象里装着
 * registry、resource、environment、problemReporter。 本书把上下文压成一个
 * {@link BeanDefinitionRegistry} 参数——这是**有意的简化， 代价是错误消息里拿不到「哪个资源」**，所以我们的
 * XmlBeanDefinitionReader 在解析失败时要自己补上资源描述。
 *
 * @see org.springframework.beans.factory.xml.BeanDefinitionDocumentReader
 */
public interface BeanDefinitionDocumentReader {

	/**
	 * 把文档里的图纸注册进注册表，返回本次注册的数量。
	 */
	int registerBeanDefinitions(Document document, BeanDefinitionRegistry registry);

}
