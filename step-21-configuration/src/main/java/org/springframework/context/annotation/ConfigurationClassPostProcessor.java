package org.springframework.context.annotation;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.GenericBeanDefinition;

/**
 * 扫一遍所有图纸，把配置类里的 {@code @Bean} 方法变成图纸。
 *
 * <p>
 * 它是 {@link BeanFactoryPostProcessor}——<b>改图纸，不造对象</b>。这个定位是必须的：{@code @Bean} 方法产出的是 Bean
 * 的定义，而定义必须在任何单例被创建之前就位，否则依赖它们的 Bean 会在启动时找不到东西。 这也是第 10 章那张「刷新十二步」里
 * {@code invokeBeanFactoryPostProcessors} 排在 {@code finishBeanFactoryInitialization}
 * 前面的原因。
 *
 * <p>
 * 两件事：
 *
 * <ol>
 * <li><b>登记</b>：每个 {@code @Bean} 方法 → 一张「工厂方法」图纸（{@code factoryBeanName} +
 * {@code factoryMethodName}）。图纸上的类名填方法返回值类型，这样按类型查 Bean 时它是可见的。</li>
 * <li><b>增强</b>（仅 full 模式）：把配置类那张图纸的类换成 {@link ConfigurationClassEnhancer}
 * 生成的子类。注意换的是<b>图纸上的类</b>，不是造出来的对象——容器随后按图纸造实例，自然就造出子类实例。 真实 Spring
 * 也是这一步（{@code ConfigurationClassPostProcessor#enhanceConfigurationClasses} 里
 * {@code beanDef.setBeanClass(enhancedClass)}）。</li>
 * </ol>
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code ConfigurationClassPostProcessor}（805 行，还有
 * {@code ConfigurationClassParser} 与 {@code ConfigurationClassBeanDefinitionReader}
 * 两个协作者）。 我们的版本只认「本类（含父类）上的 {@code @Bean} 方法」，不处理 {@code @Import}、
 * {@code @ComponentScan}、条件装配——那些是真实 Spring 的另一半。
 *
 * @see Bean
 * @see Configuration
 * @see org.springframework.context.annotation.ConfigurationClassPostProcessor
 */
public class ConfigurationClassPostProcessor implements BeanFactoryPostProcessor {

	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		// 先拍快照：下面要往图纸表里加东西，边遍历边加会乱。
		for (String beanName : beanFactory.getBeanDefinitionNames()) {
			postProcessConfigurationClass(beanFactory, beanName);
		}
	}

	private void postProcessConfigurationClass(ConfigurableListableBeanFactory beanFactory, String beanName) {
		BeanDefinition beanDefinition = beanFactory.getBeanDefinition(beanName);
		Class<?> beanClass = resolveBeanClass(beanDefinition);
		if (beanClass == null) {
			return;
		}
		Map<String, Method> beanMethods = findBeanMethods(beanClass);
		if (beanMethods.isEmpty()) {
			return;
		}
		Map<String, String> beanNamesByMethod = registerBeanMethods(beanFactory, beanName, beanMethods);
		if (isFullConfigurationClass(beanClass)) {
			ConfigMethodInterceptor interceptor = new ConfigMethodInterceptor(beanFactory, beanClass, beanMethods,
					beanNamesByMethod);
			Class<?> enhancedClass = ConfigurationClassEnhancer.enhance(beanClass,
					new ArrayList<>(beanMethods.values()), interceptor);
			((AbstractBeanDefinition) beanDefinition).setBeanClass(enhancedClass);
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
	 * 找出所有 {@code @Bean} 方法，含父类上的。按方法名索引——本章不支持同名重载。
	 * @param beanClass 配置类
	 * @return 方法名 → 方法
	 */
	private Map<String, Method> findBeanMethods(Class<?> beanClass) {
		Map<String, Method> beanMethods = new LinkedHashMap<>();
		for (Class<?> current = beanClass; current != null
				&& current != Object.class; current = current.getSuperclass()) {
			for (Method method : current.getDeclaredMethods()) {
				if (!method.isAnnotationPresent(Bean.class) || Modifier.isStatic(method.getModifiers())
						|| Modifier.isPrivate(method.getModifiers())) {
					continue;
				}
				beanMethods.putIfAbsent(method.getName(), method);
			}
		}
		return beanMethods;
	}

	/**
	 * full 模式的判据只有两条：类上有 {@code @Configuration}，且它没关掉 {@code proxyBeanMethods}。
	 *
	 * <p>
	 * 注意「类上有」是要看<b>元注解</b>的：{@code @Configuration} 自己标了 {@code @Component}， 而真实 Spring
	 * 还允许第三方的组合注解把 {@code @Configuration} 标在自己头上（{@code @SpringBootConfiguration}
	 * 就是这么来的）。本章只看直接标注。
	 * @param beanClass 候选类
	 * @return full 模式返回 {@code true}
	 */
	private boolean isFullConfigurationClass(Class<?> beanClass) {
		Configuration configuration = beanClass.getAnnotation(Configuration.class);
		return configuration != null && configuration.proxyBeanMethods();
	}

	private Map<String, String> registerBeanMethods(ConfigurableListableBeanFactory beanFactory, String configBeanName,
			Map<String, Method> beanMethods) {
		Map<String, String> beanNamesByMethod = new LinkedHashMap<>();
		for (Map.Entry<String, Method> entry : beanMethods.entrySet()) {
			Method method = entry.getValue();
			Bean bean = method.getAnnotation(Bean.class);
			String beanName = (bean.value().isEmpty() ? method.getName() : bean.value());
			beanNamesByMethod.put(method.getName(), beanName);
			if (beanFactory.containsBeanDefinition(beanName)) {
				// 已经有人登记过同名的（比如手写的 XML 图纸）：不覆盖，让显式配置赢
				continue;
			}
			GenericBeanDefinition beanDefinition = new GenericBeanDefinition();
			beanDefinition.setBeanClassName(method.getReturnType().getName());
			beanDefinition.setFactoryBeanName(configBeanName);
			beanDefinition.setFactoryMethodName(method.getName());
			if (!bean.initMethod().isEmpty()) {
				beanDefinition.setInitMethodName(bean.initMethod());
			}
			if (!bean.destroyMethod().isEmpty()) {
				beanDefinition.setDestroyMethodName(bean.destroyMethod());
			}
			// 真实 Spring 为此专门拆了一个子接口 BeanDefinitionRegistryPostProcessor——
			// 能「造 Bean」和能「改图纸表」是两种能力，这里用强转把这件事说破。
			((BeanDefinitionRegistry) beanFactory).registerBeanDefinition(beanName, beanDefinition);
		}
		return beanNamesByMethod;
	}

}
