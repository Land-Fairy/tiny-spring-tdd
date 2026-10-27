package org.springframework.core.type.classreading;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * {@link SimpleMetadataReader} 的结果：只装数据，没有任何行为。
 */
final class SimpleClassMetadata implements ClassMetadata {

	private final String className;

	private final Set<String> annotationTypes;

	private final Map<String, Map<String, String>> annotationAttributes;

	SimpleClassMetadata(String className, Set<String> annotationTypes,
			Map<String, Map<String, String>> annotationAttributes) {
		this.className = className;
		this.annotationTypes = Collections.unmodifiableSet(new LinkedHashSet<>(annotationTypes));
		this.annotationAttributes = Collections.unmodifiableMap(new LinkedHashMap<>(annotationAttributes));
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

	@Override
	public String toString() {
		return this.className + this.annotationTypes;
	}

}
