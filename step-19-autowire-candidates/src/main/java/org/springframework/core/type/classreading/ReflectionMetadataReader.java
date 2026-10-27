package org.springframework.core.type.classreading;

import java.lang.annotation.Annotation;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 用反射读元数据——也就是「先加载类，再问它」。
 *
 * <p>
 * 它短得多、也更容易写对，代价是：{@code Class.forName(name, true, loader)} 的 {@code true}
 * 表示<b>初始化</b>。那个类会被加载进来，静态初始化块会跑，类里的静态状态会生效， 如果它引用了别的类，那些类也会被牵连进来。
 *
 * <p>
 * <b>真实 Spring 对应</b>：没有直接对应物——Spring 的扫描<b>从不</b>用这条路径。 但 {@code @Configuration}
 * 场景下最终仍会加载类，那时靠的是「先筛后加载」。
 */
public class ReflectionMetadataReader implements MetadataReader {

	private final String className;

	/**
	 * 指定要读的类名。
	 * @param className 全限定类名
	 */
	public ReflectionMetadataReader(String className) {
		this.className = className;
	}

	@Override
	public ClassMetadata getClassMetadata() {
		return getClassMetadata(this.className);
	}

	/**
	 * 读指定类的元数据（顺带初始化它）。
	 * @param className 全限定类名
	 * @return 类元数据
	 */
	public ClassMetadata getClassMetadata(String className) {
		Class<?> type;
		try {
			type = Class.forName(className, true, classLoader());
		}
		catch (ClassNotFoundException ex) {
			throw new IllegalStateException("Class not found: " + className, ex);
		}
		Set<String> annotationTypes = new LinkedHashSet<>();
		Map<String, Map<String, String>> attributes = new LinkedHashMap<>();
		for (Annotation annotation : type.getAnnotations()) {
			String name = annotation.annotationType().getName();
			annotationTypes.add(name);
			attributes.put(name, Map.of());
		}
		return new ReflectiveClassMetadata(className, annotationTypes, attributes);
	}

	private ClassLoader classLoader() {
		ClassLoader loader = Thread.currentThread().getContextClassLoader();
		return (loader != null) ? loader : getClass().getClassLoader();
	}

	/**
	 * 反射路径的元数据（属性值这里不解析：本章只需要注解类型名）。
	 */
	private static final class ReflectiveClassMetadata implements ClassMetadata {

		private final String className;

		private final Set<String> annotationTypes;

		private final Map<String, Map<String, String>> annotationAttributes;

		private ReflectiveClassMetadata(String className, Set<String> annotationTypes,
				Map<String, Map<String, String>> annotationAttributes) {
			this.className = className;
			this.annotationTypes = annotationTypes;
			this.annotationAttributes = annotationAttributes;
		}

		@Override
		public String getClassName() {
			return this.className;
		}

		@Override
		public Set<String> getAnnotationTypes() {
			return this.annotationTypes;
		}

		@Override
		public String getAnnotationAttribute(String annotationType, String attributeName) {
			return this.annotationAttributes.getOrDefault(annotationType, Map.of()).get(attributeName);
		}

	}

}
