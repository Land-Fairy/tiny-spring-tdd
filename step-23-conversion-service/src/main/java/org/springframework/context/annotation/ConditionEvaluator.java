package org.springframework.context.annotation;

import java.lang.reflect.AnnotatedElement;

/**
 * 把「元素上的 {@code @Conditional}」变成一个是非答案。
 *
 * <p>
 * 三个容易写错的地方：
 * <ol>
 * <li>条件类每次<b>新建</b>一个实例（不缓存）：条件必须无状态，缓存会让它变成有状态的隐式依赖。</li>
 * <li>多个条件是<b>与 + 短路</b>：任何一个不匹配立即返回，后面的条件不问。</li>
 * <li>要沿<b>元注解</b>找 {@code @Conditional}：{@code @Profile} 就是靠这个生效的。</li>
 * </ol>
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.context.annotation.ConditionEvaluator}（191 行， 还处理
 * {@code ConfigurationPhase}——区分「解析配置类阶段」与「注册 Bean 阶段」，因为同一个条件在这两个阶段可 能有不同答案）。
 */
class ConditionEvaluator {

	private final ConditionContext context;

	ConditionEvaluator(ConditionContext context) {
		this.context = context;
	}

	/**
	 * 这个元素该不该被跳过。
	 * @param element 配置类或 {@code @Bean} 方法
	 * @return 跳过返回 {@code true}
	 */
	boolean shouldSkip(AnnotatedElement element) {
		for (Conditional conditional : AnnotationSupport.findAnnotations(element, Conditional.class)) {
			for (Class<? extends Condition> conditionClass : conditional.value()) {
				if (!instantiate(conditionClass).matches(this.context, element)) {
					return true;
				}
			}
		}
		return false;
	}

	private Condition instantiate(Class<? extends Condition> conditionClass) {
		try {
			return conditionClass.getDeclaredConstructor().newInstance();
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("Cannot instantiate condition [" + conditionClass.getName() + "]", ex);
		}
	}

}
