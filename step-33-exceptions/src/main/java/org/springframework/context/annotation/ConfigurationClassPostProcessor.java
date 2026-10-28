package org.springframework.context.annotation;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * 扫一遍所有图纸，把配置类里的 {@code @Bean} 方法变成图纸，并按条件/导入/延迟三个开关决定要不要登记。
 *
 * <p>
 * 它是 {@link BeanFactoryPostProcessor}——<b>改图纸，不造对象</b>。这个定位是必须的：{@code @Bean} 方法产出的是 Bean
 * 的定义，而定义必须在任何单例被创建之前就位，否则依赖它们的 Bean 会在启动时找不到东西。 这也是第 10 章那张「刷新十二步」里
 * {@code invokeBeanFactoryPostProcessors} 排在 {@code finishBeanFactoryInitialization}
 * 前面的原因。
 *
 * <p>
 * 第 21 章它只做两件事（登记 {@code @Bean}、增强 full 配置类）。本章给它加三个入口：
 *
 * <ol>
 * <li><b>条件</b>：配置类或 {@code @Bean} 方法上的 {@code @Conditional}（{@code @Profile}
 * 靠元注解也走这里）为假时， 不登记。<b>注意是「不登记」而不是「登记了但跳过创建」</b>——图纸上根本没有它，按类型查也查不到。</li>
 * <li><b>导入</b>：{@code @Import} 的类先登记成图纸，再递归处理（配置类要连它的 {@code @Bean} 一起）。</li>
 * <li><b>延迟</b>：{@code @Lazy} 只是给图纸上的 {@code lazyInit} 位置一。</li>
 * </ol>
 *
 * <p>
 * 为了实现 {@code @Profile}，它必须能读环境——所以本章它实现 {@link EnvironmentAware}，由上下文在调它之前把
 * {@link Environment} 递进来（真实 Spring 的 {@code ConfigurationClassPostProcessor} 也实现这个接口）。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code ConfigurationClassPostProcessor}（827 行，还有
 * {@code ConfigurationClassParser} 与 {@code ConfigurationClassBeanDefinitionReader}
 * 两个协作者）。 我们的版本只认「本类（含父类）上的 {@code @Bean} 方法」与静态的 {@code @Import} 列表， 不处理
 * {@code ImportSelector}、{@code ImportBeanDefinitionRegistrar}、{@code @ComponentScan}
 * 驱动的条件装配。
 *
 * @see Bean
 * @see Configuration
 * @see Conditional
 * @see Import
 * @see Lazy
 */
public class ConfigurationClassPostProcessor implements BeanFactoryPostProcessor, EnvironmentAware {

	private Environment environment;

	private ConfigurableListableBeanFactory beanFactory;

	private ConditionEvaluator conditionEvaluator;

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
		this.conditionEvaluator = new ConditionEvaluator(new StandardConditionContext(beanFactory, this.environment));
		// 先拍快照：下面要往图纸表里加东西，边遍历边加会乱。
		Set<String> processed = new HashSet<>();
		for (String beanName : beanFactory.getBeanDefinitionNames()) {
			processConfigurationClass(beanName, processed);
		}
	}

	/**
	 * 处理一个配置类：条件 → 导入 → {@code @Bean} 方法 → 增强。
	 * @param beanName 图纸名
	 * @param processed 已处理过的图纸名（防环：{@code @Import} 可以互相导入）
	 */
	private void processConfigurationClass(String beanName, Set<String> processed) {
		if (!processed.add(beanName)) {
			return;
		}
		BeanDefinition beanDefinition = this.beanFactory.getBeanDefinition(beanName);
		Class<?> beanClass = resolveBeanClass(beanDefinition);
		if (beanClass == null || this.conditionEvaluator.shouldSkip(beanClass)) {
			// 条件不成立：连它自己都不该留在图纸表里，否则按类型查会查到一个永远造不出来的名字
			removeIfConditional(beanName);
			return;
		}
		importClasses(beanClass, processed);
		Map<String, Method> beanMethods = findBeanMethods(beanClass, true);
		if (beanMethods.isEmpty()) {
			return;
		}
		Map<String, String> beanNamesByMethod = registerBeanMethods(beanName, beanMethods);
		if (isFullConfigurationClass(beanClass)) {
			Map<String, Method> allBeanMethods = findBeanMethods(beanClass, false);
			ConfigMethodInterceptor interceptor = new ConfigMethodInterceptor(this.beanFactory, beanClass,
					allBeanMethods, beanNamesByMethod);
			Class<?> enhancedClass = ConfigurationClassEnhancer.enhance(beanClass,
					new ArrayList<>(allBeanMethods.values()), interceptor);
			((AbstractBeanDefinition) beanDefinition).setBeanClass(enhancedClass);
		}
	}

	/**
	 * 条件不成立时把这张图纸删掉。
	 *
	 * <p>
	 * 为什么「删掉」而不是「留着但不创建」？因为按类型查 Bean（{@code getBeanNamesForType}）会遍历图纸表，
	 * 留着它，调用方就会拿到一个名字，然后 {@code getBean} 报一个听起来像配置错误、实际是条件没满足的异常。 真实 Spring
	 * 里条件为假的类根本不会被读成配置类，效果一样。
	 * @param beanName 图纸名
	 */
	private void removeIfConditional(String beanName) {
		if (this.beanFactory.containsBeanDefinition(beanName)) {
			((BeanDefinitionRegistry) this.beanFactory).removeBeanDefinition(beanName);
		}
	}

	private void importClasses(Class<?> configClass, Set<String> processed) {
		for (Import importAnnotation : AnnotationSupport.findAnnotations(configClass, Import.class)) {
			for (Class<?> importedClass : importAnnotation.value()) {
				registerImportedClass(importedClass, processed);
			}
		}
	}

	private void registerImportedClass(Class<?> importedClass, Set<String> processed) {
		String beanName = AnnotationSupport.buildBeanName(importedClass);
		if (this.beanFactory.containsBeanDefinition(beanName)) {
			checkCompatible(beanName, importedClass);
			return;
		}
		GenericBeanDefinition beanDefinition = new GenericBeanDefinition();
		beanDefinition.setBeanClassName(importedClass.getName());
		beanDefinition.setLazyInit(isLazy(importedClass));
		((BeanDefinitionRegistry) this.beanFactory).registerBeanDefinition(beanName, beanDefinition);
		// 导入的可能还是个配置类：递归（其 @Bean 方法要用「它自己的 Bean 名」当 factoryBeanName）
		processConfigurationClass(beanName, processed);
	}

	/**
	 * 名字已经存在时，允许「同一个类被导入两次」，但两个不同的类算出同一个默认 Bean 名必须当场炸。
	 *
	 * <p>
	 * 为什么不能宽容一点？{@code same.a.Foo} 与 {@code same.b.Foo} 的默认名字都是 {@code foo}，
	 * 若静默留下一个、丢掉另一个，症状是「明明 {@code @Import} 了却没生效」，而在几十个配置类里找这种 问题只能靠猜。真实 Spring 抛
	 * {@code ConflictingBeanDefinitionException}，消息也是这句话。
	 * @param beanName 撞车的名字
	 * @param importedClass 正要导入的类
	 */
	private void checkCompatible(String beanName, Class<?> importedClass) {
		String existingClassName = this.beanFactory.getBeanDefinition(beanName).getBeanClassName();
		if (existingClassName != null && !existingClassName.equals(importedClass.getName())) {
			throw new BeanDefinitionStoreException(
					"Annotation-specified bean name '" + beanName + "' for bean class [" + importedClass.getName()
							+ "] conflicts with existing, non-compatible bean definition of same name and class ["
							+ existingClassName + "]");
		}
	}

	private Class<?> resolveBeanClass(BeanDefinition beanDefinition) {
		String beanClassName = beanDefinition.getBeanClassName();
		if (beanClassName == null) {
			return null;
		}
		try {
			return Class.forName(beanClassName);
		}
		catch (ClassNotFoundException ex) {
			throw new IllegalStateException("Cannot load class [" + beanClassName + "]", ex);
		}
	}

	/**
	 * 找出所有 {@code @Bean} 方法，含父类上的，并在这里就把不满足条件的剔掉。按方法名索引——本章不支持同名重载。
	 * @param beanClass 配置类
	 * @param beanClass 配置类
	 * @param honourConditions 是否按方法上的条件过滤。增强时要拿到<b>全部</b> {@code @Bean} 方法（含被跳过的），
	 * 否则跳过的方法在生成类里没有覆写，被同类方法调用时会直接跑方法体、返回一个没人登记的野对象
	 * @return 方法名 → 方法
	 */
	private Map<String, Method> findBeanMethods(Class<?> beanClass, boolean honourConditions) {
		Map<String, Method> beanMethods = new LinkedHashMap<>();
		for (Class<?> current = beanClass; current != null
				&& current != Object.class; current = current.getSuperclass()) {
			for (Method method : current.getDeclaredMethods()) {
				if (!method.isAnnotationPresent(Bean.class) || Modifier.isStatic(method.getModifiers())
						|| Modifier.isPrivate(method.getModifiers())
						|| (honourConditions && this.conditionEvaluator.shouldSkip(method))) {
					continue;
				}
				beanMethods.putIfAbsent(method.getName(), method);
			}
		}
		return beanMethods;
	}

	/**
	 * full 模式的判据只有两条：类上有 {@code @Configuration}（含元注解链），且它没关掉 {@code proxyBeanMethods}。
	 * @param beanClass 候选类
	 * @return full 模式返回 {@code true}
	 */
	private boolean isFullConfigurationClass(Class<?> beanClass) {
		List<Configuration> configurations = AnnotationSupport.findAnnotations(beanClass, Configuration.class);
		return !configurations.isEmpty() && configurations.get(0).proxyBeanMethods();
	}

	private Map<String, String> registerBeanMethods(String configBeanName, Map<String, Method> beanMethods) {
		Map<String, String> beanNamesByMethod = new LinkedHashMap<>();
		for (Map.Entry<String, Method> entry : beanMethods.entrySet()) {
			Method method = entry.getValue();
			Bean bean = method.getAnnotation(Bean.class);
			String beanName = (bean.value().isEmpty() ? method.getName() : bean.value());
			beanNamesByMethod.put(method.getName(), beanName);
			if (this.beanFactory.containsBeanDefinition(beanName)) {
				// 已经有人登记过同名的（比如手写的 XML 图纸）：不覆盖，让显式配置赢
				continue;
			}
			GenericBeanDefinition beanDefinition = new GenericBeanDefinition();
			beanDefinition.setBeanClassName(method.getReturnType().getName());
			beanDefinition.setFactoryBeanName(configBeanName);
			beanDefinition.setFactoryMethodName(method.getName());
			beanDefinition.setLazyInit(isLazy(method));
			if (!bean.initMethod().isEmpty()) {
				beanDefinition.setInitMethodName(bean.initMethod());
			}
			if (!bean.destroyMethod().isEmpty()) {
				beanDefinition.setDestroyMethodName(bean.destroyMethod());
			}
			// 真实 Spring 为此专门拆了一个子接口 BeanDefinitionRegistryPostProcessor——
			// 能「造 Bean」和能「改图纸表」是两种能力，这里用强转把这件事说破。
			((BeanDefinitionRegistry) this.beanFactory).registerBeanDefinition(beanName, beanDefinition);
		}
		return beanNamesByMethod;
	}

	/**
	 * {@code @Lazy} 是「有且值为 true」才算延迟，所以 {@code @Lazy(false)} 要能关掉组合注解里的设置。
	 * @param element 类或方法
	 * @return 延迟则返回 {@code true}
	 */
	private boolean isLazy(AnnotatedElement element) {
		List<Lazy> lazies = AnnotationSupport.findAnnotations(element, Lazy.class);
		return !lazies.isEmpty() && lazies.get(0).value();
	}

	/**
	 * 条件能看到的世界的实现：只读、只有两样东西。
	 */
	private static final class StandardConditionContext implements ConditionContext {

		private final ConfigurableListableBeanFactory beanFactory;

		private final Environment environment;

		private StandardConditionContext(ConfigurableListableBeanFactory beanFactory, Environment environment) {
			this.beanFactory = beanFactory;
			this.environment = environment;
		}

		@Override
		public ConfigurableListableBeanFactory getBeanFactory() {
			return this.beanFactory;
		}

		@Override
		public Environment getEnvironment() {
			return this.environment;
		}

	}

}
