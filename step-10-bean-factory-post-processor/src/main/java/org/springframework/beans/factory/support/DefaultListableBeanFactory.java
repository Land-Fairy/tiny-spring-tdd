package org.springframework.beans.factory.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.NotWritablePropertyException;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.NoSuchBeanException;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.StandardReflectionParameterNameDiscoverer;

/**
 * 容器本体：图纸表 + 单例池 + 父子回退 + 对容器外对象的能力。
 *
 * <p>
 * <b>第 06 章唯一的变化</b>（此后各章沿用这段说明）：这个类的<b>方法</b>几乎一行没改，变的是它<b>声明的身份</b>——从「一个具体的类」
 * 变成「{@link ConfigurableListableBeanFactory} 的一个实现」。所有新增的能力都长在接口上，
 * 而下面每个方法都标注了它「属于哪一层」，这样读的时候能对上：
 *
 * <table border="1">
 * <caption>方法 → 层</caption>
 * <tr>
 * <th>方法</th>
 * <th>声明它的接口</th>
 * </tr>
 * <tr>
 * <td>{@code getBean} / {@code containsBean} / {@code isSingleton} / {@code isPrototype}
 * / {@code getType}</td>
 * <td>{@link BeanFactory}</td>
 * </tr>
 * <tr>
 * <td>{@code getBeanNamesForType} / {@code getBeansOfType} /
 * {@code getBeanDefinitionCount} / {@code getBeanDefinitionNames}</td>
 * <td>{@link org.springframework.beans.factory.ListableBeanFactory}</td>
 * </tr>
 * <tr>
 * <td>{@code getParentBeanFactory} / {@code containsLocalBean}</td>
 * <td>{@link org.springframework.beans.factory.HierarchicalBeanFactory}</td>
 * </tr>
 * <tr>
 * <td>{@code createBean(Class)} / {@code applyBeanPropertyValues} /
 * {@code initializeBean}</td>
 * <td>{@link org.springframework.beans.factory.config.AutowireCapableBeanFactory}</td>
 * </tr>
 * <tr>
 * <td>{@code setParentBeanFactory} / {@code setParameterNameDiscoverer}</td>
 * <td>{@link org.springframework.beans.factory.config.ConfigurableBeanFactory}</td>
 * </tr>
 * <tr>
 * <td>{@code preInstantiateSingletons}</td>
 * <td>{@link ConfigurableListableBeanFactory}</td>
 * </tr>
 * <tr>
 * <td>{@code registerBeanDefinition} / {@code removeBeanDefinition} …</td>
 * <td>{@link BeanDefinitionRegistry}（一直独立在外的那条支线）</td>
 * </tr>
 * </table>
 *
 * <p>
 * <b>为什么仍然只有一个类</b>：真实 Spring 把这些能力分散在 {@code AbstractBeanFactory} →
 * {@code AbstractAutowireCapableBeanFactory} → {@code DefaultListableBeanFactory}
 * 三层父类上（再加 {@code FactoryBeanRegistrySupport} 与
 * {@code DefaultSingletonBeanRegistry}），一共五层继承。本书压成一层，是因为本章的题目是
 * 「<b>调用方看到几层接口</b>」而不是「实现有几层继承」——前者决定依赖的方向，后者只是代码组织方式。 真实的五层继承链在第 32 章逐行精读时再补。
 *
 * @see ConfigurableListableBeanFactory
 * @see BeanDefinitionRegistry
 * @see DefaultSingletonBeanRegistry
 */
public class DefaultListableBeanFactory extends DefaultSingletonBeanRegistry
		implements ConfigurableListableBeanFactory, BeanDefinitionRegistry {

	/**
	 * 名字 → 图纸。注意它是图纸表，不是对象表，与第 01 章那个存对象的 {@code Map} 恰好相反。
	 */
	private final Map<String, BeanDefinition> beanDefinitionMap = new ConcurrentHashMap<>(64);

	/**
	 * 名字的<b>注册顺序</b>。
	 * <p>
	 * 为什么不直接用 {@code beanDefinitionMap.keySet()}？因为 {@link ConcurrentHashMap} 的迭代顺序
	 * 由哈希决定，与注册顺序无关。第 03 章演示过同一件事：两个 Bean 谁先被取到，取决于它们的
	 * {@code hashCode}。对「按名字取」没有影响，但对<b>启动期批量造单例</b>影响很大——
	 * {@code preInstantiateSingletons()} 按什么顺序造，就决定了：
	 * <ul>
	 * <li>启动日志里的顺序（排查启动慢时全靠它）</li>
	 * <li>启动失败时，报的是哪一个 Bean 的错（不可复现的报错最耗人）</li>
	 * <li>依赖关系恰好由顺序满足时，能不能侥幸跑通</li>
	 * </ul>
	 * 真实 Spring 因此额外维护一个 {@code List<String>}（{@code :210}），并在
	 * {@code preInstantiateSingletons()} 里<b>按这个列表</b>迭代（{@code :777}）。
	 * <p>
	 * 这里用 {@link CopyOnWriteArrayList}：写少读多，而且迭代期间不允许被并发修改 （真实 Spring 用一个
	 * {@code volatile List} 加「整体替换」，效果相同，实现更省内存）。
	 */
	private final List<String> beanDefinitionNames = new CopyOnWriteArrayList<>();

	/**
	 * 父容器。{@code volatile} 是因为 {@code setParentBeanFactory} 可能在另一个线程里被调用 （Spring MVC
	 * 初始化子容器时就是这样），而读它的 {@code getBean} 到处都是。
	 */
	private volatile BeanFactory parentBeanFactory;

	/**
	 * 问「参数叫什么名字」的策略。第 04 章它是 {@code final}，本章改成可替换—— 因为 {@code ConfigurableBeanFactory}
	 * 承诺了 {@code setParameterNameDiscoverer}。
	 */
	private ParameterNameDiscoverer parameterNameDiscoverer = new StandardReflectionParameterNameDiscoverer();

	/**
	 * 构造器解析器。懒创建：它必须在 {@code parameterNameDiscoverer} 被替换之后才建，否则拿的是旧的。
	 */
	private ConstructorResolver constructorResolver;

	// ------------------------------------------------------------------
	// ConfigurableBeanFactory 这一层：可以改的设置
	// ------------------------------------------------------------------

	@Override
	public void setParentBeanFactory(BeanFactory parentBeanFactory) {
		this.parentBeanFactory = parentBeanFactory;
	}

	@Override
	public void setParameterNameDiscoverer(ParameterNameDiscoverer parameterNameDiscoverer) {
		this.parameterNameDiscoverer = parameterNameDiscoverer;
		this.constructorResolver = null;
	}

	@Override
	public ParameterNameDiscoverer getParameterNameDiscoverer() {
		return this.parameterNameDiscoverer;
	}

	// ------------------------------------------------------------------
	// HierarchicalBeanFactory 这一层：父子容器
	// ------------------------------------------------------------------

	@Override
	public BeanFactory getParentBeanFactory() {
		return this.parentBeanFactory;
	}

	/**
	 * 只看本容器。真实 Spring 的同一个方法在 {@code AbstractBeanFactory#containsLocalBean}。
	 * @param name Bean 的名字
	 * @return 本容器有则为 {@code true}
	 */
	@Override
	public boolean containsLocalBean(String name) {
		return containsBeanDefinition(name) || containsSingleton(name);
	}

	// ------------------------------------------------------------------
	// BeanFactory 这一层：取一个 Bean，以及关于它的问题
	// ------------------------------------------------------------------

	/**
	 * 按名字取 Bean。这是「图纸 → 对象」的唯一入口。
	 *
	 * <p>
	 * 本章在这里加了一层<b>回退</b>：本容器查不到就去问父容器。真实 Spring 的顺序一模一样，写在
	 * {@code AbstractBeanFactory#doGetBean} 里：先 {@code getSingleton} 本地，再
	 * {@code getParentBeanFactory()}，父容器有就 {@code parentBeanFactory.getBean(name)}。
	 *
	 * <p>
	 * 一个容易被忽略的细节：判断「本容器有没有」用的是 {@link #containsLocalBean}， <b>不是</b>
	 * {@code containsBean}——后者会把父容器也算进来，于是回退永远不会发生。
	 * @param beanName Bean 的名字
	 * @return Bean 实例
	 */
	@Override
	public Object getBean(String beanName) {
		if (!containsLocalBean(beanName)) {
			return getBeanFromParent(beanName);
		}
		BeanDefinition beanDefinition = this.beanDefinitionMap.get(beanName);
		if (beanDefinition == null) {
			// 只有单例池里有：registerSingleton 直接注册的现成对象，没有图纸。
			return getSingleton(beanName);
		}
		if (beanDefinition.isPrototype()) {
			return doCreateBean(beanName, beanDefinition);
		}
		return getSingleton(beanName, () -> doCreateBean(beanName, beanDefinition));
	}

	@Override
	public <T> T getBean(String beanName, Class<T> requiredType) {
		Object bean = getBean(beanName);
		if (!requiredType.isInstance(bean)) {
			throw new NoSuchBeanException("Bean named '" + beanName + "' is expected to be of type '"
					+ requiredType.getName() + "' but was actually of type '" + bean.getClass().getName() + "'");
		}
		return requiredType.cast(bean);
	}

	@Override
	public boolean containsBean(String beanName) {
		return containsLocalBean(beanName)
				|| (this.parentBeanFactory != null && this.parentBeanFactory.containsBean(beanName));
	}

	@Override
	public boolean isSingleton(String beanName) {
		BeanDefinition beanDefinition = this.beanDefinitionMap.get(beanName);
		if (beanDefinition != null) {
			return !beanDefinition.isPrototype();
		}
		return containsSingleton(beanName) || requireParent(beanName).isSingleton(beanName);
	}

	@Override
	public boolean isPrototype(String beanName) {
		BeanDefinition beanDefinition = this.beanDefinitionMap.get(beanName);
		if (beanDefinition != null) {
			return beanDefinition.isPrototype();
		}
		return !containsSingleton(beanName) && requireParent(beanName).isPrototype(beanName);
	}

	/**
	 * 这个名字对应的类型。优先看图纸（不需要造对象），其次看单例池里已造好的那个。
	 * @param beanName Bean 的名字
	 * @return 类型
	 */
	@Override
	public Class<?> getType(String beanName) {
		BeanDefinition beanDefinition = this.beanDefinitionMap.get(beanName);
		if (beanDefinition != null) {
			return resolveBeanClass(beanName, beanDefinition);
		}
		if (containsSingleton(beanName)) {
			return getSingleton(beanName).getClass();
		}
		BeanFactory parent = this.parentBeanFactory;
		if (parent != null && parent.containsBean(beanName)) {
			return parent.getType(beanName);
		}
		throw new NoSuchBeanException("No bean named '" + beanName + "' available");
	}

	// ------------------------------------------------------------------
	// ListableBeanFactory 这一层：枚举
	// ------------------------------------------------------------------

	/**
	 * 类型匹配的所有名字。<b>只读图纸上的类名，不造对象</b>。
	 *
	 * <p>
	 * 这是本章唯一需要「两条来源」的方法：有图纸的按图纸算，只有单例的（{@code registerSingleton} 注册的现成对象） 按实例算。真实 Spring
	 * 里这件事复杂得多——它要区分「能不能为了确定类型而把 {@code FactoryBean} 造出来」， 那就是 {@code allowEagerInit}
	 * 参数的来历。
	 * @param type 目标类型，{@code null} 表示全部
	 * @return 名字数组
	 */
	@Override
	public String[] getBeanNamesForType(Class<?> type) {
		if (type == null) {
			return getBeanDefinitionNames();
		}
		java.util.List<String> names = new java.util.ArrayList<>();
		// 按注册顺序遍历，不按哈希顺序：调用方拿到的数组顺序必须稳定，
		// 否则「哪个 Bean 先被处理」会变成不可复现的随机结果。
		for (String beanName : this.beanDefinitionNames) {
			Class<?> beanClass = resolveBeanClass(beanName, this.beanDefinitionMap.get(beanName));
			if (type.isAssignableFrom(beanClass)) {
				names.add(beanName);
			}
		}
		for (String singletonName : getSingletonNames()) {
			if (this.beanDefinitionMap.containsKey(singletonName)) {
				continue;
			}
			if (type.isInstance(getSingleton(singletonName))) {
				names.add(singletonName);
			}
		}
		return names.toArray(new String[0]);
	}

	@Override
	public <T> Map<String, T> getBeansOfType(Class<T> type) {
		Map<String, T> result = new LinkedHashMap<>();
		for (String beanName : getBeanNamesForType(type)) {
			result.put(beanName, type.cast(getBean(beanName)));
		}
		return result;
	}

	// ------------------------------------------------------------------
	// AutowireCapableBeanFactory 这一层：对容器外的对象干活
	// ------------------------------------------------------------------

	/**
	 * 造一个对象，<b>不注册</b>。构造器参数仍然从容器里找。
	 * @param beanClass 要造的类
	 * @param <T> 类型
	 * @return 新对象
	 */
	@Override
	public <T> T createBean(Class<T> beanClass) {
		Object bean = getConstructorResolver().instantiate(beanClass.getName(), beanClass);
		invokeInitMethods(beanClass.getName(), bean);
		return beanClass.cast(bean);
	}

	@Override
	public void applyBeanPropertyValues(Object existingBean, String beanName) {
		populateBean(beanName, getBeanDefinition(beanName), existingBean);
	}

	@Override
	public Object initializeBean(Object existingBean, String beanName) {
		invokeInitMethods(beanName, existingBean);
		return existingBean;
	}

	// ------------------------------------------------------------------
	// ConfigurableListableBeanFactory 这一层
	// ------------------------------------------------------------------

	/**
	 * 把所有非懒加载的单例一次性造出来。
	 *
	 * <p>
	 * 这个方法的<b>存在理由</b>值得记住：它把「配置错误」从运行期（第一个请求进来时）提前到启动期。 真实 Spring 在
	 * {@code AbstractApplicationContext#refresh()} 的
	 * {@code finishBeanFactoryInitialization(beanFactory)} 里调它（第 09 章）。
	 */
	@Override
	public void preInstantiateSingletons() {
		for (String beanName : getBeanDefinitionNames()) {
			BeanDefinition beanDefinition = getBeanDefinition(beanName);
			if (beanDefinition.isAbstract() || beanDefinition.isPrototype() || beanDefinition.isLazyInit()) {
				continue;
			}
			getBean(beanName);
		}
	}

	// ------------------------------------------------------------------
	// BeanDefinitionRegistry 这一层：图纸的增删改查
	// ------------------------------------------------------------------

	@Override
	public void registerBeanDefinition(String beanName, BeanDefinition beanDefinition) {
		if (beanName == null || beanName.isEmpty()) {
			throw new IllegalArgumentException("Bean name must not be empty");
		}
		if (beanDefinition == null) {
			throw new IllegalArgumentException("BeanDefinition must not be null");
		}
		BeanDefinition existing = this.beanDefinitionMap.put(beanName, beanDefinition);
		if (existing == null) {
			// 覆盖注册不改变顺序：同一个名字始终留在它第一次出现的位置上，
			// 否则「启动顺序」会随着一次配置覆盖而悄悄变化。
			this.beanDefinitionNames.add(beanName);
		}
	}

	/**
	 * 删除一张图纸。注意它<b>不动单例池</b>：图纸撕了，已经按它造出来的对象还在池子里。
	 * @param beanName Bean 的名字
	 */
	@Override
	public void removeBeanDefinition(String beanName) {
		BeanDefinition removed = this.beanDefinitionMap.remove(beanName);
		if (removed == null) {
			throw new NoSuchBeanException("No bean named '" + beanName + "' available");
		}
		this.beanDefinitionNames.remove(beanName);
	}

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
		return this.beanDefinitionNames.toArray(new String[0]);
	}

	@Override
	public int getBeanDefinitionCount() {
		return this.beanDefinitionMap.size();
	}

	@Override
	public boolean isBeanNameInUse(String beanName) {
		return containsBeanDefinition(beanName) || containsSingleton(beanName);
	}

	// ------------------------------------------------------------------
	// 内部实现
	// ------------------------------------------------------------------

	private Object getBeanFromParent(String beanName) {
		BeanFactory parent = this.parentBeanFactory;
		if (parent != null && parent.containsBean(beanName)) {
			return parent.getBean(beanName);
		}
		throw new NoSuchBeanException("No bean named '" + beanName + "' available");
	}

	private BeanFactory requireParent(String beanName) {
		BeanFactory parent = this.parentBeanFactory;
		if (parent == null || !parent.containsBean(beanName)) {
			throw new NoSuchBeanException("No bean named '" + beanName + "' available");
		}
		return parent;
	}

	/**
	 * 把图纸上的类名变成 {@code Class}。
	 * @param beanName 只用于错误消息
	 * @param beanDefinition 图纸
	 * @return 类对象
	 */
	private Class<?> resolveBeanClass(String beanName, BeanDefinition beanDefinition) {
		String beanClassName = beanDefinition.getBeanClassName();
		if (beanClassName == null) {
			throw new IllegalStateException("Bean definition for '" + beanName + "' has no bean class name");
		}
		try {
			return Class.forName(beanClassName);
		}
		catch (ClassNotFoundException ex) {
			throw new IllegalStateException(
					"Failed to instantiate bean '" + beanName + "' of class [" + beanClassName + "]", ex);
		}
	}

	private ConstructorResolver getConstructorResolver() {
		ConstructorResolver resolver = this.constructorResolver;
		if (resolver == null) {
			resolver = new ConstructorResolver(this, this.parameterNameDiscoverer);
			this.constructorResolver = resolver;
		}
		return resolver;
	}

	/**
	 * 按图纸造一个对象，然后填属性、调初始化回调。三步的顺序是契约。
	 * @param beanName Bean 的名字，只用于错误消息
	 * @param beanDefinition 图纸
	 * @return 新对象
	 */
	private Object doCreateBean(String beanName, BeanDefinition beanDefinition) {
		Class<?> beanClass = resolveBeanClass(beanName, beanDefinition);
		Object bean = getConstructorResolver().instantiate(beanName, beanClass);
		populateBean(beanName, beanDefinition, bean);
		invokeInitMethods(beanName, bean);
		return bean;
	}

	/**
	 * 把图纸里的属性指令写进<b>已经造好</b>的对象。
	 * @param beanName Bean 名字
	 * @param beanDefinition 这张图纸
	 * @param bean 已经造好的对象
	 */
	protected void populateBean(String beanName, BeanDefinition beanDefinition, Object bean) {
		PropertyValues propertyValues = beanDefinition.getPropertyValues();
		if (propertyValues.isEmpty()) {
			return;
		}
		BeanWrapper beanWrapper = new BeanWrapperImpl(bean);
		try {
			beanWrapper.setPropertyValues(resolvePlaceholders(beanName, propertyValues));
		}
		catch (TypeMismatchException | NotWritablePropertyException ex) {
			// 加壳：外层只说「哪个 Bean 失败了」，细节留在 cause 上（真实 Spring :1757-1760 同款）。
			throw new BeanCreationException(beanName,
					"Error setting property values for bean '" + beanName + "': " + ex.getMessage(), ex);
		}
	}

	/**
	 * 把图纸上的占位值换成真值：{@code ref="x"} 在这一刻变成对象，{@code value="..."} 解包成字符串。
	 *
	 * <p>
	 * 没有这一步，第 08 章的 XML 图纸就只是一堆字符串——{@code <property name="greeter"
	 * ref="greeter"/>} 会被当作「字段 greeter 的值是字符串 "greeter"」， 于是填充阶段抛类型不匹配（§2.2 那个红测试）。
	 * @param beanName 正在填充的 Bean 名字（只用于异常消息）
	 * @param propertyValues 图纸上的原始值
	 * @return 解析后的值
	 */
	private PropertyValues resolvePlaceholders(String beanName, PropertyValues propertyValues) {
		BeanDefinitionValueResolver resolver = new BeanDefinitionValueResolver(this);
		MutablePropertyValues resolved = new MutablePropertyValues();
		for (PropertyValue propertyValue : propertyValues.getPropertyValues()) {
			resolved.add(propertyValue.getName(),
					resolver.resolveValueIfNecessary(propertyValue.getName(), propertyValue.getValue()));
		}
		return resolved;
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
