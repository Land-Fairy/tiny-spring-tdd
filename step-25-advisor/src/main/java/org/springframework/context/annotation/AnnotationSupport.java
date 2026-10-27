package org.springframework.context.annotation;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 沿<b>元注解链</b>找注解。
 *
 * <p>
 * 上一章的缺陷单里有一条：「{@code @SpringBootConfiguration} 会被判成 lite，因为只看直接标注」。这一章必须补上——
 * 不是因为它更漂亮，而是因为 {@code @Profile} 自己就是这么写的：
 *
 * <pre>{@code
 * &#64;Conditional(ProfileCondition.class)
 * public @interface Profile { ... }
 * }</pre>
 *
 * 于是「找 {@code @Conditional}」这件事天然要求「顺着注解的注解往上爬」。
 *
 * <p>
 * 算法是广度优先 + 一个 {@code seen} 集合防环（{@code @Documented}、{@code @Target} 这些 JDK 元注解互相标注，
 * 不防环会转死）。这只手写三十行的东西，真实 Spring 是
 * {@code MergedAnnotations}（六百多行的完整实现：属性覆盖、别名、重复注解、{@code @Inherited} 语义）。
 * 本书只做「找得到、读得出」两件事。
 *
 * <p>
 * 注意一个<b>能力边界</b>：这里能读的注解必须已经被 JVM 加载（{@code RetentionPolicy.RUNTIME}）。第 17 章那套
 * 读字节码的路子不需要加载类，两者不通用。
 */
final class AnnotationSupport {

	private AnnotationSupport() {
	}

	/**
	 * 找出直接标注的与元注解链上所有的指定注解（按发现顺序）。
	 * @param element 被注解的元素（类比方法都实现了它）
	 * @param type 要找的注解类型
	 * @param <A> 注解类型
	 * @return 找到的注解实例，找不到时返回空列表
	 */
	static <A extends Annotation> List<A> findAnnotations(AnnotatedElement element, Class<A> type) {
		List<A> found = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		Deque<Annotation> queue = new ArrayDeque<>(List.of(element.getAnnotations()));
		while (!queue.isEmpty()) {
			Annotation annotation = queue.removeFirst();
			Class<? extends Annotation> annotationType = annotation.annotationType();
			if (isJdkAnnotation(annotationType) || !seen.add(annotationType.getName())) {
				continue;
			}
			if (annotationType == type) {
				found.add(type.cast(annotation));
				continue;
			}
			queue.addAll(List.of(annotationType.getAnnotations()));
		}
		return found;
	}

	/**
	 * 有没有这个注解（含元注解链）。
	 * @param element 被注解的元素
	 * @param type 注解类型
	 * @return 有则 {@code true}
	 */
	static boolean hasAnnotation(AnnotatedElement element, Class<? extends Annotation> type) {
		return !findAnnotations(element, type).isEmpty();
	}

	/**
	 * 按真实 Spring 的规则生成默认 Bean 名：简单类名首字母小写，内部类取最后一个 {@code $} 之后的部分 （{@code Outer$Inner} →
	 * {@code inner}）。
	 * @param type 类
	 * @return 默认 Bean 名
	 */
	static String buildBeanName(Class<?> type) {
		String shortName = type.getName();
		int separator = shortName.lastIndexOf('.');
		if (separator != -1) {
			shortName = shortName.substring(separator + 1);
		}
		separator = shortName.lastIndexOf('$');
		if (separator != -1) {
			shortName = shortName.substring(separator + 1);
		}
		if (shortName.length() > 1 && Character.isUpperCase(shortName.charAt(1))) {
			return shortName;
		}
		return Character.toLowerCase(shortName.charAt(0)) + shortName.substring(1);
	}

	private static boolean isJdkAnnotation(Class<? extends Annotation> annotationType) {
		return annotationType.getName().startsWith("java.lang.");
	}

}
