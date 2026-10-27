package org.springframework.beans.factory.annotation;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;

/**
 * 让 {@code @Autowired} 生效的那段代码。<b>注解没有魔力，这个类才是魔力。</b>
 *
 * <p>
 * <b>为什么需要它</b>：一个 Bean 的字段想拿到另一个 Bean，除了构造器（第 04 章）与 setter（第 05 章），还有第三条路——直接改字段。字段是
 * {@code private} 的，容器凭什么能改？凭
 * {@code Field#trySetAccessible()}。于是「写个注解就有依赖」的全部秘密就是：<b>有人在属性填充阶段
 * 扫了一遍字段，看到注解就用反射塞进去</b>。
 *
 * <p>
 * <b>为什么是 {@code InstantiationAwareBeanPostProcessor} 而不是普通
 * {@code BeanPostProcessor}</b>： 因为字段注入必须发生在<b>属性填充阶段</b>（和 XML 的 {@code ref}
 * 同一时刻），而不是初始化之后。 如果放在 {@code postProcessBeforeInitialization} 里，虽然「大部分时候也够用」，但 {@code
 * afterPropertiesSet} 里看不到依赖，而且 XML 值与注解值的先后会变得不可推理。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor}
 * （1113 行）。关键行号：{@code postProcessMergedBeanDefinition} :292、
 * {@code postProcessProperties} :506、{@code findAutowiringMetadata} :543、字段注入的
 * {@code AutowiredFieldElement#inject} :752、方法注入 :830。真实实现把「哪些字段要注入」缓存进
 * {@code ConcurrentHashMap}（本书每次扫一遍，见 3.4 节的限制）；按类型挑候选的完整决策链（多个候选怎么办、 {@code @Qualifier}
 * 怎么消歧）在 {@code DefaultListableBeanFactory#doResolveDependency}，第 19 章讲。
 *
 * @see org.springframework.beans.factory.annotation.Autowired
 * @see org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor
 */
public class AutowiredAnnotationBeanPostProcessor implements InstantiationAwareBeanPostProcessor {

	private final ListableBeanFactory beanFactory;

	/**
	 * 构造器需要一个<b>能按类型枚举</b>的工厂：按类型找候选要遍历图纸与单例两边， 那是 {@link ListableBeanFactory} 才有的能力（第 06
	 * 章）。
	 * <p>
	 * 真实 Spring 不用构造器参数，而是实现 {@code BeanFactoryAware} 让容器回注（第 12 章）—— 因为写成 XML 的 Bean
	 * 没法传构造器参数给自己。
	 * @param beanFactory 用来解析依赖的工厂
	 */
	public AutowiredAnnotationBeanPostProcessor(ListableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	@Override
	public PropertyValues postProcessProperties(PropertyValues propertyValues, Object bean, String beanName) {
		try {
			injectFields(bean, beanName);
		}
		catch (BeanCreationException ex) {
			// 已经是「带名字 + 带原因」的了，不重复加壳（真实 Spring 同款：catch (BeanCreationException ex) {
			// throw ex; }）
			throw ex;
		}
		catch (RuntimeException ex) {
			throw new BeanCreationException(beanName,
					"Injection of autowired dependencies failed for bean '" + beanName + "'", ex);
		}
		return propertyValues;
	}

	/**
	 * 扫这个类（含父类）的所有字段，把带 {@code @Autowired} 的填上。
	 * @param bean 目标对象
	 * @param beanName Bean 名字，只用于异常消息
	 */
	private void injectFields(Object bean, String beanName) {
		for (Field field : fieldsOf(bean.getClass())) {
			Autowired autowired = field.getAnnotation(Autowired.class);
			if (autowired == null) {
				continue;
			}
			Object value = resolveField(beanName, field, autowired);
			if (value == null) {
				continue;
			}
			writeField(beanName, bean, field, value);
		}
	}

	/**
	 * 按字段类型去容器里要一个 Bean。
	 * @param beanName 正在被注入的 Bean 名字
	 * @param field 字段
	 * @param autowired 注解（用来判断是否必填）
	 * @return 解析到的值；可选依赖缺失时返回 {@code null}
	 */
	private Object resolveField(String beanName, Field field, Autowired autowired) {
		Class<?> type = field.getType();
		String[] candidates = this.beanFactory.getBeanNamesForType(type);
		if (candidates.length == 0) {
			if (!autowired.required()) {
				return null;
			}
			throw new BeanCreationException(beanName, "Could not autowire field: " + field + " (bean '" + beanName
					+ "'): no bean of type [" + type.getName() + "] is registered");
		}
		if (candidates.length > 1) {
			// 真实 Spring 抛 NoUniqueBeanDefinitionException，并在消息里列出候选名字（第 19 章讲怎么消歧）。
			throw new BeanCreationException(beanName,
					"Could not autowire field: " + field + " (bean '" + beanName
							+ "'): expected single matching bean but found " + candidates.length + ": "
							+ String.join(", ", candidates));
		}
		return this.beanFactory.getBean(candidates[0]);
	}

	/**
	 * 用反射写进去。{@code private} 字段也能写——这就是「注解比 setter 方便」的代价所在。
	 * @param beanName Bean 名字
	 * @param bean 目标对象
	 * @param field 字段
	 * @param value 要写入的值
	 */
	private void writeField(String beanName, Object bean, Field field, Object value) {
		if (!field.trySetAccessible()) {
			throw new BeanCreationException(beanName,
					"Could not make field accessible: " + field + " (bean '" + beanName + "')");
		}
		try {
			field.set(bean, value);
		}
		catch (IllegalAccessException ex) {
			throw new BeanCreationException(beanName, "Could not inject field: " + field + " (bean '" + beanName + "')",
					ex);
		}
	}

	/**
	 * 收集要检查的字段：从当前类往上走到 {@code Object}，跳过静态与合成字段。
	 * @param beanClass 目标类型
	 * @return 字段列表
	 */
	private List<Field> fieldsOf(Class<?> beanClass) {
		List<Field> fields = new ArrayList<>();
		for (Class<?> current = beanClass; current != null
				&& current != Object.class; current = current.getSuperclass()) {
			for (Field field : current.getDeclaredFields()) {
				if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
					fields.add(field);
				}
			}
		}
		return fields;
	}

}
