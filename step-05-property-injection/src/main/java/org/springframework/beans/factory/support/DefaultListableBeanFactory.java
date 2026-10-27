package org.springframework.beans.factory.support;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.NoSuchBeanException;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.NotWritablePropertyException;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.StandardReflectionParameterNameDiscoverer;

/**
 * 「图纸表 + 单例池」两件套的最小容器。
 *
 * <p>
 * <b>为什么需要它</b>：第 02 章的容器只有图纸表，于是 {@code getBean} 每次都要按图纸重新造一个对象——图纸上明明写着「单例」，
 * 行为却是「永远不同一个」。本章把状态拆成两份：
 * <ol>
 * <li><b>图纸表</b>（{@code beanDefinitionMap}）——回答「这个 Bean 该怎么造」，注册时不产生对象；
 * <li><b>单例池</b>（父类 {@link DefaultSingletonBeanRegistry}）——回答「这个 Bean 是否已经造好了」。
 * </ol>
 * 两份状态各有自己的最小接口（{@link BeanDefinitionRegistry} 与
 * {@link org.springframework.beans.factory.config.SingletonBeanRegistry}），职责不混，谁也不需要知道对方的内部结构。
 *
 * <p>
 * <b>两个状态的边界由作用域决定</b>：单例走池子（同一个名字永远同一个对象），原型不进池子（每次都是新的）。 这条分叉是 {@link #getBean(String)}
 * 里唯一的分支。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.beans.factory.support.DefaultListableBeanFactory}， 它
 * {@code implements BeanDefinitionRegistry}，而单例池的能力来自父类链的最下端
 * {@code DefaultSingletonBeanRegistry}。我们把那条四层的父类链压成了「直接继承单例池」（第 06 章会把
 * {@code BeanFactory} 的接口分层补回来，第 32 章再补完整调用链）。
 *
 * <p>
 * <b>本章补上的能力</b>：{@link #createBean} 不再自己调 {@code getDeclaredConstructor()}，而是把「造一个对象」
 * 整个交给 {@link ConstructorResolver}——于是带参数的构造器也能用，依赖由容器递归提供（第 04 章）。
 *
 * <p>
 * <b>本章剩下的缺陷</b>：只按<b>构造器</b>注入，{@code setter} 注入还没有（第 05 章）；图纸上的 {@code initMethodName}
 * 仍没有代码去读（第 13 章）；循环依赖会死锁（第 30 章）。
 *
 * @see BeanDefinition
 * @see DefaultSingletonBeanRegistry
 */
public class DefaultListableBeanFactory extends DefaultSingletonBeanRegistry implements BeanDefinitionRegistry {

	/**
	 * 名字 → 图纸。注意它是图纸表，不是对象表，与第 01 章那个存对象的 {@code Map} 恰好相反。
	 *
	 * <p>
	 * 用 {@link ConcurrentHashMap} 而不是 {@link java.util.HashMap}：注册通常发生在启动阶段、单线程，
	 * 但一旦多个线程同时 {@code getBean}，读写同一张表必须有可见性与原子性保证。真实 Spring 用的也是
	 * {@code ConcurrentHashMap}（{@code DefaultListableBeanFactory.java:195}）。
	 */
	private final Map<String, BeanDefinition> beanDefinitionMap = new ConcurrentHashMap<>(64);

	/**
	 * 问「参数叫什么名字」的策略。
	 *
	 * <p>
	 * 做成字段而不是常量，是为了让容器可被替换实现（真实 Spring 有 {@code setParameterNameDiscoverer}，{@code @Bean}
	 * 方法、{@code @ConstructorProperties} 都会换掉它）。 默认用基于 {@code -parameters} 的那个标准实现。
	 */
	private final ParameterNameDiscoverer parameterNameDiscoverer = new StandardReflectionParameterNameDiscoverer();

	/**
	 * 构造器解析器。它与本类互相持有：本类在 {@link #createBean} 里调它，它在本类里查依赖。
	 */
	private final ConstructorResolver constructorResolver = new ConstructorResolver(this, this.parameterNameDiscoverer);

	/**
	 * 注册一张图纸。不会造任何对象。
	 * @param beanName Bean 的名字
	 * @param beanDefinition 图纸
	 */
	@Override
	public void registerBeanDefinition(String beanName, BeanDefinition beanDefinition) {
		if (beanName == null || beanName.isEmpty()) {
			throw new IllegalArgumentException("Bean name must not be empty");
		}
		if (beanDefinition == null) {
			throw new IllegalArgumentException("BeanDefinition must not be null");
		}
		this.beanDefinitionMap.put(beanName, beanDefinition);
	}

	/**
	 * 删除一张图纸。
	 *
	 * <p>
	 * 注意它<b>不动单例池</b>：把图纸撕掉，已经按它造出来的对象还在池子里。真实 Spring 的行为也是如此——
	 * {@code removeBeanDefinition} 只操作 {@code beanDefinitionMap}；要让对象也消失，得显式调
	 * {@code destroySingleton}。职责分离的代价就体现在这里：两份状态不会自动同步。
	 * @param beanName Bean 的名字
	 */
	@Override
	public void removeBeanDefinition(String beanName) {
		BeanDefinition removed = this.beanDefinitionMap.remove(beanName);
		if (removed == null) {
			throw new NoSuchBeanException("No bean named '" + beanName + "' available");
		}
	}

	/**
	 * 取一张图纸。
	 * @param beanName Bean 的名字
	 * @return 图纸
	 */
	@Override
	public BeanDefinition getBeanDefinition(String beanName) {
		BeanDefinition beanDefinition = this.beanDefinitionMap.get(beanName);
		if (beanDefinition == null) {
			throw new NoSuchBeanException("No bean named '" + beanName + "' available");
		}
		return beanDefinition;
	}

	@Override
	public boolean containsBeanDefinition(String beanName) {
		return this.beanDefinitionMap.containsKey(beanName);
	}

	@Override
	public String[] getBeanDefinitionNames() {
		return this.beanDefinitionMap.keySet().toArray(new String[0]);
	}

	@Override
	public int getBeanDefinitionCount() {
		return this.beanDefinitionMap.size();
	}

	/**
	 * 这个 beanName 是否已被占用（已有图纸，或已是某个单例的名字）。
	 * @param beanName Bean 的名字
	 * @return 占用则为 {@code true}
	 */
	@Override
	public boolean isBeanNameInUse(String beanName) {
		return containsBeanDefinition(beanName) || containsSingleton(beanName);
	}

	/**
	 * 当前的参数名策略。
	 * @return 参数名策略
	 */
	public ParameterNameDiscoverer getParameterNameDiscoverer() {
		return this.parameterNameDiscoverer;
	}

	/**
	 * 按名字取 Bean。这是「图纸 → 对象」的唯一入口。
	 *
	 * <p>
	 * 分叉只有一处：原型作用域绕过池子，每次都造新的；其余情况一律走
	 * {@link DefaultSingletonBeanRegistry#getSingleton(String, ObjectFactory)}，由单例池负责
	 * 「已经有了就还给你，没有才调这个工厂」。
	 *
	 * <p>
	 * 这里的 lambda 就是真实 Spring 里那句
	 * {@code sharedInstance = getSingleton(beanName, () -> createBean(beanName, mbd, args))}：
	 * <b>把「怎么造」包装成一个延迟执行的工厂交出去</b>，造不造、造几次、谁加锁，全部由池子决定。
	 * 这是本章最重要的一个设计动作——工厂对象把「创建动作」变成了可传递的一等值。
	 * @param beanName Bean 的名字
	 * @return Bean 实例
	 */
	public Object getBean(String beanName) {
		BeanDefinition beanDefinition = getBeanDefinition(beanName);
		if (beanDefinition.isPrototype()) {
			return createBean(beanName, beanDefinition);
		}
		return getSingleton(beanName, () -> createBean(beanName, beanDefinition));
	}

	/**
	 * 按图纸造一个对象。
	 *
	 * <p>
	 * 分两步：先把类名变成 {@code Class}（失败说明配置写错了），再交给 {@link ConstructorResolver}
	 * 决定用哪个构造器、每个参数填什么。第二步里发生的一切递归 （造 service 要先造 repository）都由解析器通过
	 * {@link #getBean(String)} 重新进入本类完成。
	 * @param beanName Bean 的名字，只用于错误消息
	 * @param beanDefinition 图纸
	 * @return 新对象
	 */
	private Object createBean(String beanName, BeanDefinition beanDefinition) {
		String beanClassName = beanDefinition.getBeanClassName();
		if (beanClassName == null) {
			throw new IllegalStateException("Bean definition for '" + beanName + "' has no bean class name");
		}
		Class<?> beanClass;
		try {
			beanClass = Class.forName(beanClassName);
		}
		catch (ClassNotFoundException ex) {
			throw new IllegalStateException(
					"Failed to instantiate bean '" + beanName + "' of class [" + beanClassName + "]", ex);
		}
		Object bean = this.constructorResolver.instantiate(beanName, beanClass);
		populateBean(beanName, beanDefinition, bean);
		invokeInitMethods(beanName, bean);
		return bean;
	}

	/**
	 * 把图纸里的属性指令写进<b>已经造好</b>的对象。
	 *
	 * <p>
	 * 这个方法只做两件事：判空跳过、以及给下层异常加壳。所有「怎么找 setter、怎么判类型」都在 {@link BeanWrapperImpl} 里。真实 Spring
	 * 的同一个方法在 {@code AbstractAutowireCapableBeanFactory#populateBean}（:1403），它在中间还夹了
	 * 两件本章不做的事：{@code InstantiationAwareBeanPostProcessor#postProcessAfterInstantiation}
	 * 与 {@code postProcessProperties}（第 11 章）、以及按名字/按类型的 autowire（第 19 章）。
	 * @param beanName Bean 名字
	 * @param beanDefinition 这张图纸
	 * @param bean 已经由构造器造好的对象
	 */
	protected void populateBean(String beanName, BeanDefinition beanDefinition, Object bean) {
		PropertyValues propertyValues = beanDefinition.getPropertyValues();
		if (propertyValues.isEmpty()) {
			return;
		}
		BeanWrapper beanWrapper = new BeanWrapperImpl(bean);
		try {
			beanWrapper.setPropertyValues(propertyValues);
		}
		catch (TypeMismatchException | NotWritablePropertyException ex) {
			// 加壳：外层只说「哪个 Bean 失败了」，细节留在 cause 上（真实 Spring :1757-1760 同款）。
			throw new BeanCreationException(beanName,
					"Error setting property values for bean '" + beanName + "': " + ex.getMessage(), ex);
		}
	}

	/**
	 * 调用初始化回调。本章只认 {@link InitializingBean} 这一种，第 13 章补齐
	 * {@code @PostConstruct}、{@code init-method}、{@code BeanPostProcessor}。
	 * @param beanName Bean 名字
	 * @param bean 已经填好属性的对象
	 */
	protected void invokeInitMethods(String beanName, Object bean) {
		if (bean instanceof InitializingBean initializingBean) {
			try {
				initializingBean.afterPropertiesSet();
			}
			catch (Exception ex) {
				throw new BeanCreationException(beanName,
						"Invocation of afterPropertiesSet of bean '" + beanName + "' failed", ex);
			}
		}
	}

}
