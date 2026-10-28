package org.springframework.core.convert.support;

import java.util.ArrayList;
import java.util.List;

import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.ConverterNotFoundException;
import org.springframework.core.convert.ConverterRegistry;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;
import org.springframework.util.ClassUtils;

/**
 * 转换服务的通用实现：登记、查找、执行。
 *
 * <p>
 * 这类有三件事，顺序不能反：<b>先把两个类型都归成包装类型</b>，<b>再找转换器</b>，<b>最后才执行</b>。 归一是第一步，因为
 * {@code int.class} 和 {@code Integer.class} 在 {@code isAssignableFrom} 眼里是两个不同的类，
 * 不归一会让“一半的登记项永远命中不了”。
 *
 * <p>
 * 查找只看两个问题：<b>源类型能不能喂给它</b>（{@code declaredSource.isAssignableFrom(actualSource)}），
 * <b>它吐出来的东西能不能当目标类型用</b>（{@code requiredTarget.isAssignableFrom(declaredTarget)}）。
 * 两个方向都是 {@code isAssignableFrom}，但左右两边<b>不是同一对</b> —— 写反了就成了「要求源类型是实际类型的子类」， 会得到
 * {@code String
 * → Object} 找不到转换器这种荒谬答案。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.core.convert.support.GenericConversionService}（685
 * 行）。 它把「解析出来的转换器」缓存在一个以「源类型 + 目标类型」为键的映射里，并把类的继承链与接口链都压平成一个列表 （{@code
 * getClassHierarchy}）；本书每次现找、按「离实际类型近的优先」排序，够用且好读。
 */
public class GenericConversionService implements ConversionService, ConverterRegistry {

	private final List<ConvertiblePair> converters = new ArrayList<>();

	private final List<ConverterFactoryAdapter> factories = new ArrayList<>();

	/**
	 * 登记一对一转换器。后登记的<b>不会</b>覆盖先登记的：查找时按「离实际源类型最近」排序， 距离相同则先登记的优先，所以重复登记是无效动作而不是覆盖动作。
	 * @param sourceType 源类型
	 * @param targetType 目标类型
	 * @param converter 转换器
	 * @param <S> 源类型
	 * @param <T> 目标类型
	 */
	@Override
	public <S, T> void addConverter(Class<S> sourceType, Class<T> targetType, Converter<S, T> converter) {
		this.converters.add(new ConvertiblePair(ClassUtils.resolvePrimitiveIfNecessary(sourceType),
				ClassUtils.resolvePrimitiveIfNecessary(targetType), converter));
	}

	/**
	 * 登记一族转换器。
	 * @param sourceType 源类型
	 * @param factory 工厂
	 * @param <S> 源类型
	 * @param <R> 目标类型上界
	 */
	@Override
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public <S, R> void addConverterFactory(Class<S> sourceType, ConverterFactory<S, R> factory) {
		this.factories.add(new ConverterFactoryAdapter(ClassUtils.resolvePrimitiveIfNecessary(sourceType), factory));
	}

	@Override
	public boolean canConvert(Class<?> sourceType, Class<?> targetType) {
		if (targetType == null) {
			return false;
		}
		if (sourceType == null) {
			return !targetType.isPrimitive();
		}
		return findConverter(sourceType, targetType) != null;
	}

	@Override
	public <T> T convert(Object source, Class<T> targetType) {
		if (targetType == null) {
			throw new IllegalArgumentException("Target type must not be null");
		}
		if (source == null) {
			if (targetType.isPrimitive()) {
				throw new ConversionFailedException(null, null, targetType,
						new IllegalArgumentException("source is null whereas the target type is primitive"));
			}
			return null;
		}
		Class<?> sourceType = source.getClass();
		Converter<Object, Object> converter = findConverter(sourceType, targetType);
		if (converter == null) {
			throw new ConverterNotFoundException(sourceType, targetType);
		}
		Object result;
		try {
			result = converter.convert(source);
		}
		catch (RuntimeException ex) {
			throw new ConversionFailedException(source, sourceType, targetType, ex);
		}
		if (result == null && targetType.isPrimitive()) {
			throw new ConversionFailedException(source, sourceType, targetType,
					new IllegalArgumentException("converter returned null for a primitive target type"));
		}
		// 这里必须是「未检查强转」而不是 targetType.cast(result)：Class#cast 在基本类型的 Class 上永远抛
		// ClassCastException —— int.class 没有实例可言，它连 Object 都不是。真实 Spring 同样是 (T) 强转。
		@SuppressWarnings("unchecked")
		T converted = (T) result;
		return converted;
	}

	@SuppressWarnings("unchecked")
	private Converter<Object, Object> findConverter(Class<?> sourceType, Class<?> targetType) {
		Class<?> source = ClassUtils.resolvePrimitiveIfNecessary(sourceType);
		Class<?> target = ClassUtils.resolvePrimitiveIfNecessary(targetType);
		if (target.isAssignableFrom(source)) {
			return (Converter<Object, Object>) NoOpConverter.INSTANCE;
		}
		ConvertiblePair best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (ConvertiblePair pair : this.converters) {
			if (!pair.sourceType.isAssignableFrom(source) || !target.isAssignableFrom(pair.targetType)) {
				continue;
			}
			int distance = distance(source, pair.sourceType);
			if (distance < bestDistance) {
				best = pair;
				bestDistance = distance;
			}
		}
		if (best != null) {
			return (Converter<Object, Object>) best.converter;
		}
		for (ConverterFactoryAdapter adapter : this.factories) {
			if (adapter.sourceType.isAssignableFrom(source)) {
				Converter<Object, Object> converter = adapter.forTargetType(target);
				if (converter != null) {
					return converter;
				}
			}
		}
		return null;
	}

	/**
	 * 从 {@code actual} 走到 {@code declared} 最短要几步：自身 0 步，父类逐级加一，接口接在父类之后。
	 * <p>
	 * 这个数只用来排序，取值不精确没关系；但「找不到路」必须返回一个足够大的数，否则一个不相关的转换器会 被当成最优解。
	 * @param actual 实际类型
	 * @param declared 转换器声明的源类型
	 * @return 步数
	 */
	private static int distance(Class<?> actual, Class<?> declared) {
		int steps = 0;
		for (Class<?> current = actual; current != null; current = current.getSuperclass()) {
			if (current == declared) {
				return steps;
			}
			steps++;
		}
		int interfaceSteps = steps;
		for (Class<?> current = actual; current != null; current = current.getSuperclass()) {
			if (hasInterface(current, declared)) {
				return interfaceSteps;
			}
			interfaceSteps++;
		}
		return Integer.MAX_VALUE;
	}

	private static boolean hasInterface(Class<?> type, Class<?> wanted) {
		for (Class<?> candidate : type.getInterfaces()) {
			if (candidate == wanted || hasInterface(candidate, wanted)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 「源类型 + 目标类型 + 转换器」三件套。
	 */
	private static final class ConvertiblePair {

		private final Class<?> sourceType;

		private final Class<?> targetType;

		private final Converter<?, ?> converter;

		private ConvertiblePair(Class<?> sourceType, Class<?> targetType, Converter<?, ?> converter) {
			this.sourceType = sourceType;
			this.targetType = targetType;
			this.converter = converter;
		}

	}

	/**
	 * 把工厂包成「给定目标类型就给我一个转换器」的样子。
	 * <p>
	 * 这里用了裸类型 {@code ConverterFactory}：工厂的核心价值就是「目标类型到查找时才定」， 这让它在泛型上天生不安分 —— 一个
	 * {@code StringToEnumConverterFactory} 得服务 {@code 任意 Enum 子类}， 而 Java 的泛型表达不出「所有
	 * {@code Enum} 子类」。真实 Spring 用两层嵌套泛型加一处 {@code (T)} 强转把这件事压下去，
	 * 本书把强转集中在这一处并加注释，而不是散落在每个工厂里。
	 */
	private static final class ConverterFactoryAdapter {

		private final Class<?> sourceType;

		private final ConverterFactory factory;

		private ConverterFactoryAdapter(Class<?> sourceType, ConverterFactory factory) {
			this.sourceType = sourceType;
			this.factory = factory;
		}

		@SuppressWarnings("unchecked")
		private Converter<Object, Object> forTargetType(Class<?> targetType) {
			Converter converter = this.factory.getConverter(targetType);
			return converter == null ? null : converter::convert;
		}

	}

	/**
	 * 目标类型本来就能装下源类型时的「转换器」：原样返回。
	 */
	private enum NoOpConverter implements Converter<Object, Object> {

		INSTANCE;

		@Override
		public Object convert(Object source) {
			return source;
		}

	}

}
