package org.springframework.context.annotation;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.ScannedGenericBeanDefinition;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.type.classreading.ClassMetadata;
import org.springframework.core.type.classreading.SimpleMetadataReader;

/**
 * 「去包里找候选组件」——扫描器的上半截，只管<b>找</b>，不管注册。
 *
 * <p>
 * 三个阶段：
 * <ol>
 * <li>把一个包名变成一堆 {@code .class} 文件（本类只支持目录，jar 留给第 18 章）</li>
 * <li>逐个读字节码拿到注解（{@link SimpleMetadataReader}）</li>
 * <li>判断「它算不算候选」，包括<b>元注解推导</b>：{@code @Service} 头上标着 {@code @Component}</li>
 * </ol>
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider}
 * （约 600 行：还带 {@code TypeFilter}、{@code @Conditional}、{@code excludeFilters}、jar 扫描、
 * 内部类判定、{@code @Lookup} 判定等）。
 */
public class ClassPathScanningCandidateComponentProvider {

	/**
	 * 默认的「算数」注解。
	 */
	public static final String COMPONENT_ANNOTATION = "org.springframework.context.annotation.Component";

	private final ClassLoader classLoader;

	/**
	 * 第 18 章：把「找 .class 文件」这件事交给通配解析器，本类不再自己遍历目录。
	 */
	private final ResourcePatternResolver resourcePatternResolver;

	private String includeAnnotationType = COMPONENT_ANNOTATION;

	/**
	 * 用当前线程的上下文类加载器。
	 */
	public ClassPathScanningCandidateComponentProvider() {
		ClassLoader loader = Thread.currentThread().getContextClassLoader();
		this.classLoader = (loader != null) ? loader : getClass().getClassLoader();
		this.resourcePatternResolver = new PathMatchingResourcePatternResolver(this.classLoader);
	}

	/**
	 * 只认某一种注解（默认认 {@link Component} 及其派生注解）。
	 * @param annotationType 注解类型
	 */
	public void setIncludeAnnotation(Class<? extends Annotation> annotationType) {
		this.includeAnnotationType = annotationType.getName();
	}

	/**
	 * 扫描一个包（递归含子包），返回候选 Bean 定义。
	 * @param basePackage 包名，例如 {@code org.springframework.samples.ch17}
	 * @return 候选定义集合
	 */
	public Set<BeanDefinition> findCandidateComponents(String basePackage) {
		Set<BeanDefinition> candidates = new LinkedHashSet<>();
		for (Resource resource : findClassResources(basePackage)) {
			ClassMetadata metadata = new SimpleMetadataReader(resource).getClassMetadata();
			if (isCandidateComponent(metadata)) {
				candidates.add(new ScannedGenericBeanDefinition(metadata));
			}
		}
		return candidates;
	}

	/**
	 * 是不是候选：直接标了目标注解，或者标的注解<b>头上</b>标了目标注解。
	 * @param metadata 类元数据
	 * @return 是则为 {@code true}
	 */
	protected boolean isCandidateComponent(ClassMetadata metadata) {
		for (String annotationType : metadata.getAnnotationTypes()) {
			if (annotationType.equals(this.includeAnnotationType) || isMetaAnnotated(annotationType, new HashSet<>())) {
				return true;
			}
		}
		return false;
	}

	private boolean isMetaAnnotated(String annotationType, Set<String> visited) {
		if (!visited.add(annotationType)) {
			return false; // 防环：注解之间可以互相标注
		}
		ClassMetadata metadata = readMetadata(annotationType);
		if (metadata == null) {
			return false;
		}
		for (String candidate : metadata.getAnnotationTypes()) {
			if (candidate.equals(this.includeAnnotationType) || isMetaAnnotated(candidate, visited)) {
				return true;
			}
		}
		return false;
	}

	private ClassMetadata readMetadata(String className) {
		String path = "classpath:" + className.replace('.', '/') + ".class";
		Resource resource = new org.springframework.core.io.DefaultResourceLoader().getResource(path);
		if (!resource.exists()) {
			return null;
		}
		return new SimpleMetadataReader(resource).getClassMetadata();
	}

	/**
	 * 把一个包名变成一堆 {@code .class} 资源。
	 *
	 * <p>
	 * 第 17 章这里是「自己去 getResources + 递归 listFiles」，只支持目录； 第 18 章换成一条通配路径，交给
	 * {@link PathMatchingResourcePatternResolver}—— <b>顺手就把 jar 里的类也扫到了</b>（第 17
	 * 章缺陷表里的第一条）。
	 * @param basePackage 包名
	 * @return 类文件资源
	 */
	private List<Resource> findClassResources(String basePackage) {
		String packageSearchPath = ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX + basePackage.replace('.', '/')
				+ "/**/*.class";
		try {
			return Arrays.asList(this.resourcePatternResolver.getResources(packageSearchPath));
		}
		catch (IOException ex) {
			throw new IllegalStateException("Failed to scan package " + basePackage, ex);
		}
	}

}
