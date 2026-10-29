package org.springframework.core.type.classreading;

import java.util.Set;

/**
 * 一个类的「元数据」：类名与它头上的注解。
 *
 * <p>
 * 注意这个接口<b>刻意不含</b>{@code Class} 对象——它的实现之一（{@link SimpleMetadataReader}） 全程不加载类。只要签名里出现
 * {@code Class}，这条路就走不通。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.core.type.ClassMetadata}（类名、父类、接口） 与
 * {@code AnnotationMetadata}（注解）两个接口；本书合成一个。
 *
 * @see org.springframework.core.type.ClassMetadata
 */
public interface ClassMetadata {

	/**
	 * 全限定类名。
	 * @return 例如 {@code org.springframework.samples.ch17.GreetingService}
	 */
	String getClassName();

	/**
	 * 类上直接标注的注解的<b>全限定名</b>集合；没有注解时返回空集合（不返回 {@code null}）。
	 * @return 注解全限定名集合
	 */
	Set<String> getAnnotationTypes();

	/**
	 * 是否直接标注了某个注解（<b>不</b>包含元注解推导，推导由扫描器负责）。
	 * @param annotationType 注解全限定名
	 * @return 标了则为 {@code true}
	 */
	default boolean hasAnnotation(String annotationType) {
		return getAnnotationTypes().contains(annotationType);
	}

	/**
	 * 读某个注解的单个 {@code String} 属性值。
	 * @param annotationType 注解全限定名
	 * @param attributeName 属性名
	 * @return 属性值；不存在或不是字符串则为 {@code null}
	 */
	String getAnnotationAttribute(String annotationType, String attributeName);

}
