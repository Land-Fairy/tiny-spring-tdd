package org.springframework.beans.factory.support;

import org.springframework.core.type.classreading.ClassMetadata;

/**
 * 扫描出来的 Bean 定义：比手写 XML 多带一份「元数据」。
 *
 * <p>
 * 为什么值得单独一个类？因为元数据是<b>读取阶段的产物</b>，而 {@code BeanDefinition} 是 <b>运行阶段的契约</b>。以后再有人问「这个
 * Bean 是扫出来的还是配出来的」，看类型就知道。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.context.annotation.ScannedGenericBeanDefinition} ——它
 * {@code extends GenericBeanDefinition implements AnnotatedBeanDefinition}，比本书多一层
 * 「注解元数据」接口。
 *
 * @see org.springframework.context.annotation.ScannedGenericBeanDefinition
 */
public class ScannedGenericBeanDefinition extends GenericBeanDefinition {

	private final ClassMetadata metadata;

	/**
	 * 用读到的元数据造一张图纸。
	 * @param metadata 类元数据
	 */
	public ScannedGenericBeanDefinition(ClassMetadata metadata) {
		this.metadata = metadata;
		setBeanClassName(metadata.getClassName());
	}

	/**
	 * 扫描期的元数据。
	 * @return 元数据
	 */
	public ClassMetadata getMetadata() {
		return this.metadata;
	}

}
