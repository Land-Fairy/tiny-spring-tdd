package org.springframework.beans.factory.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
import org.springframework.beans.SimpleTypeConverter;
import org.springframework.core.convert.ConversionService;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.Aware;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanIsNotAFactoryException;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.Scope;
import org.springframework.beans.factory.config.StringValueResolver;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.StandardReflectionParameterNameDiscoverer;

/**
 * 容器本体：图纸表 + 单例池 + 父子回退 + 对容器外对象的能力。
 *
 * <p>
 * <b>本章唯一的变化</b>：这个类的<b>方法</b>几乎一行没改，变的是它<b>声明的身份</b>——从「一个具体的类」
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
public class DefaultListableBeanFactory extends FactoryBeanRegistrySupport
		implements ConfigurableListableBeanFactory, BeanDefinitionRegistry {

	/**
	 * 名字 → 图纸。注意它是图纸表，不是对象表，与第 01 章那个存对象的 {@code Map} 恰好相反。
	 */
	private final Map<String, BeanDefinition> beanDefinitionMap = new ConcurrentHashMap<>(64);

	/**
	 * 已注册的作用域表：名字 → 实现。
	 *
	 * <p>
	 * 单例与原型是<b>内建</b>的（写在 if/else 里，不需要登记），其余作用域都得登记才能用—— 因为容器不可能事先知道「request」长什么样。
	 */
	private final Map<String, Scope> scopes = new ConcurrentHashMap<>();

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
	 * 装上的后处理器。<b>顺序就是调用顺序</b>，所以用能安全遍历的容器（后处理器可以在遍历中被装上）。 真实 Spring 里是
	 * {@code CopyOnWriteArrayList}（:206）。
	 */
	private final List<BeanPostProcessor> beanPostProcessors = new CopyOnWriteArrayList<>();

	/**
	 * 文本解析器链。**有序、可叠加**，先装的先问。
	 *
	 * <p>
	 * 与 {@code beanPostProcessors} 一样用 {@code CopyOnWriteArrayList}：读多写极少（启动时装一两个，
	 * 运行期只读），复制写比加锁简单。
	 */
	private final List<StringValueResolver> embeddedValueResolvers = new CopyOnWriteArrayList<>();

	/**
	 * 类型转换器。真实 Spring 是 {@code getTypeConverter()} 返回的可替换策略（{@code setTypeConverter}），
	 * 本书固定一个实例。
	 */
	private SimpleTypeConverter typeConverter = new SimpleTypeConverter();

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
	public Object getBean(String name) {
		if (!containsLocalBean(transformedBeanName(name))) {
			return getBeanFromParent(name);
		}
		String beanName = transformedBeanName(name);
		BeanDefinition beanDefinition = this.beanDefinitionMap.get(beanName);
		if (beanDefinition == null) {
			// 只有单例池里有：registerSingleton 直接注册的现成对象，没有图纸。
			return getObjectForBeanInstance(getSingleton(beanName), name, beanName, null);
		}
		return getObjectForBeanInstance(getBeanInstance(name, beanName, beanDefinition), name, beanName,
				beanDefinition);
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

	/**
	 * 按作用域取「Bean 本体」（可能是工厂本身，也可能是产品之前的中间物）。
	 * @param name 调用方给的名字（可能带 {@code &}）
	 * @param beanName 去掉前缀后的名字
	 * @param beanDefinition 图纸
	 * @return 容器造出来的那个对象
	 */
	private Object getBeanInstance(String name, String beanName, BeanDefinition beanDefinition) {
		if (beanDefinition.isPrototype()) {
			return doCreateBean(beanName, beanDefinition);
		}
		if (beanDefinition.isSingleton()) {
			return getSingleton(beanName, () -> doCreateBean(beanName, beanDefinition));
		}
		Scope scope = requireScope(beanName, beanDefinition.getScope());
		return scope.get(beanName, () -> {
			Object created = doCreateBean(beanName, beanDefinition);
			scope.registerDestructionCallback(beanName, () -> destroyScopedInstance(beanName, created));
			return created;
		});
	}

	/**
	 * 作用域对象的销毁：作用域结束时被回调（不是容器关闭时）。
	 * @param beanName Bean 名字
	 * @param instance 该作用域内的实例
	 */
	private void destroyScopedInstance(String beanName, Object instance) {
		if (DisposableBeanAdapter.requiresDestruction(instance, beanName, this.beanPostProcessors, null)) {
			new DisposableBeanAdapter(instance, beanName, this.beanPostProcessors, null).destroy();
		}
	}

	/**
	 * 取已注册的作用域，没有就报错。
	 * @param beanName Bean 名字（用于错误消息）
	 * @param scopeName 图纸上写的作用域名
	 * @return 作用域实现
	 */
	private Scope requireScope(String beanName, String scopeName) {
		Scope scope = this.scopes.get(scopeName);
		if (scope == null) {
			throw new NoSuchBeanException(
					"No Scope registered for scope name '" + scopeName + "' (bean '" + beanName + "')");
		}
		return scope;
	}

	/**
	 * 这张图纸描述的是不是一个 {@code FactoryBean}。
	 * @param beanName Bean 名字
	 * @param beanDefinition 图纸
	 * @return 是则 {@code true}
	 */
	private boolean isFactoryBean(String beanName, BeanDefinition beanDefinition) {
		return FactoryBean.class.isAssignableFrom(resolveBeanClass(beanName, beanDefinition));
	}

	/**
	 * 把 {@code &} 前缀去掉。
	 * @param name 调用方给的名字
	 * @return 真正的 beanName
	 */
	private String transformedBeanName(String name) {
		return (name.startsWith(BeanFactory.FACTORY_BEAN_PREFIX)
				? name.substring(BeanFactory.FACTORY_BEAN_PREFIX.length()) : name);
	}

	/**
	 * 调用方要的是工厂本身（带 {@code &} 前缀）吗？
	 * @param name 调用方给的名字
	 * @return 是则 {@code true}
	 */
	private boolean isFactoryDereference(String name) {
		return name.startsWith(BeanFactory.FACTORY_BEAN_PREFIX);
	}

	/**
	 * 决定「返回工厂还是返回产品」的唯一入口。
	 *
	 * <p>
	 * 真实 Spring 的同名方法在 {@code AbstractBeanFactory:1857}，逻辑一模一样：先看前缀， 再看实例是不是
	 * {@code FactoryBean}，最后决定要不要走 {@code getObjectFromFactoryBean}。
	 * @param beanInstance 容器造出来的对象
	 * @param name 调用方给的名字（可能带 {@code &}）
	 * @param beanName 去掉前缀后的名字
	 * @param beanDefinition 图纸，可能为 {@code null}
	 * @return 工厂本身，或它生产的产品
	 */
	private Object getObjectForBeanInstance(Object beanInstance, String name, String beanName,
			BeanDefinition beanDefinition) {
		if (isFactoryDereference(name)) {
			if (!(beanInstance instanceof FactoryBean)) {
				throw new BeanIsNotAFactoryException(beanName, beanInstance.getClass());
			}
			return beanInstance;
		}
		if (!(beanInstance instanceof FactoryBean<?> factoryBean)) {
			return beanInstance;
		}
		Object cached = getCachedObjectForFactoryBean(beanName);
		if (cached != null) {
			return cached;
		}
		return getObjectFromFactoryBean(factoryBean, beanName, beanDefinition != null);
	}

	@Override
	public void registerScope(String scopeName, Scope scope) {
		this.scopes.put(scopeName, scope);
	}

	@Override
	public Scope getRegisteredScope(String scopeName) {
		return this.scopes.get(scopeName);
	}

	@Override
	public String[] getRegisteredScopeNames() {
		return this.scopes.keySet().toArray(new String[0]);
	}

	/**
	 * 产品出厂前的后处理：让 after 链也作用在 FactoryBean 的产品上。
	 * @param object 产品
	 * @param beanName 工厂的名字
	 * @return 处理后的产品
	 */
	@Override
	protected Object postProcessObjectFromFactoryBean(Object object, String beanName) {
		return applyBeanPostProcessorsAfterInitialization(object, beanName);
	}

	@Override
	public boolean containsBean(String beanName) {
		return containsLocalBean(beanName)
				|| (this.parentBeanFactory != null && this.parentBeanFactory.containsBean(beanName));
	}

	@Override
	public boolean isSingleton(String name) {
		String beanName = transformedBeanName(name);
		BeanDefinition beanDefinition = this.beanDefinitionMap.get(beanName);
		if (beanDefinition == null) {
			if (containsSingleton(beanName)) {
				return !isFactoryDereference(name) || getSingleton(beanName) instanceof FactoryBean;
			}
			return requireParent(beanName).isSingleton(name);
		}
		if (isFactoryDereference(name)) {
			// 问「工厂本身是不是单例」：工厂作为 Bean 的单例性看图纸。
			return beanDefinition.isSingleton();
		}
		if (!isFactoryBean(beanName, beanDefinition)) {
			return beanDefinition.isSingleton();
		}
		// 问「产品是不是单例」：这只能问工厂自己。真实 Spring 同样是 getBean(&name) 之后再问 isSingleton()。
		return ((FactoryBean<?>) getBean(BeanFactory.FACTORY_BEAN_PREFIX + beanName)).isSingleton();
	}

	@Override
	public boolean isPrototype(String name) {
		String beanName = transformedBeanName(name);
		BeanDefinition beanDefinition = this.beanDefinitionMap.get(beanName);
		if (beanDefinition == null) {
			return !containsSingleton(beanName) && requireParent(beanName).isPrototype(name);
		}
		if (isFactoryDereference(name) || !isFactoryBean(beanName, beanDefinition)) {
			return beanDefinition.isPrototype();
		}
		return !((FactoryBean<?>) getBean(BeanFactory.FACTORY_BEAN_PREFIX + beanName)).isSingleton();
	}

	/**
	 * 这个名字对应的类型。优先看图纸（不需要造对象），其次看单例池里已造好的那个。
	 * @param beanName Bean 的名字
	 * @return 类型
	 */
	@Override
	public Class<?> getType(String name) {
		String beanName = transformedBeanName(name);
		BeanDefinition beanDefinition = this.beanDefinitionMap.get(beanName);
		if (beanDefinition != null && !isFactoryDereference(name) && isFactoryBean(beanName, beanDefinition)) {
			// 产品类型：问工厂拿（真实 Spring 用 getTypeForFactoryBean 并做缓存）。
			Class<?> objectType = ((FactoryBean<?>) getBean(BeanFactory.FACTORY_BEAN_PREFIX + beanName))
				.getObjectType();
			if (objectType != null) {
				return objectType;
			}
		}
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
		invokeInitMethods(beanClass.getName(), bean, null);
		return beanClass.cast(bean);
	}

	@Override
	public void applyBeanPropertyValues(Object existingBean, String beanName) {
		populateBean(beanName, getBeanDefinition(beanName), existingBean);
	}

	@Override
	public Object initializeBean(Object existingBean, String beanName) {
		BeanDefinition beanDefinition = (containsBeanDefinition(beanName) ? getBeanDefinition(beanName) : null);
		return initializeBeanInternal(beanName, existingBean, beanDefinition);
	}

	/**
	 * 装上一个后处理器。
	 * @param beanPostProcessor 要装的后处理器
	 */
	@Override
	public void addBeanPostProcessor(BeanPostProcessor beanPostProcessor) {
		this.beanPostProcessors.add(beanPostProcessor);
	}

	/**
	 * 已装上的后处理器数量。
	 * @return 数量
	 */
	@Override
	public int getBeanPostProcessorCount() {
		return this.beanPostProcessors.size();
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
		Object bean = (beanDefinition.getFactoryMethodName() != null
				? instantiateUsingFactoryMethod(beanName, beanDefinition)
				: getConstructorResolver().instantiate(beanName, resolveBeanClass(beanName, beanDefinition)));
		populateBean(beanName, beanDefinition, bean);
		Object initializedBean = initializeBeanInternal(beanName, bean, beanDefinition);
		// 第 13 章：只有单例才登记销毁。prototype 的生死由调用方负责，容器从没管过它。
		if (beanDefinition.isSingleton()) {
			registerDisposableBeanIfNecessary(beanName, initializedBean, beanDefinition);
		}
		return initializedBean;
	}

	/**
	 * 按图纸上的「工厂 Bean + 工厂方法」造对象。<b>第 21 章</b>。
	 *
	 * <p>
	 * 与构造器路径的区别只有一句：对象不是「类 new 出来的」，而是<b>另一个 Bean 上的一个方法返回的</b>。 所以第一步永远是把工厂 Bean
	 * 先取出来——这也是为什么 {@code @Bean} 方法产出的 Bean 一定晚于配置类本身。
	 *
	 * <p>
	 * <b>参数</b>：按类型解析。容器里该类型恰好一个 Bean 时才成功，零个或多个都算失败——本章不做 {@code @Qualifier} 消歧（第 19
	 * 章那条决策链挂在注入点上，不在工厂方法参数上）。
	 * @param beanName Bean 的名字，只用于错误消息
	 * @param beanDefinition 图纸
	 * @return 工厂方法的返回值
	 */
	private Object instantiateUsingFactoryMethod(String beanName, BeanDefinition beanDefinition) {
		Object factoryBean = (beanDefinition.getFactoryBeanName() != null ? getBean(beanDefinition.getFactoryBeanName())
				: null);
		if (factoryBean == null) {
			throw new BeanCreationException(beanName,
					"No factory bean for factory method '" + beanDefinition.getFactoryMethodName() + "'");
		}
		Method factoryMethod = findFactoryMethod(beanName, factoryBean.getClass(),
				beanDefinition.getFactoryMethodName());
		Object[] args = resolveFactoryMethodArguments(beanName, factoryMethod);
		try {
			FactoryMethodInProgress.begin(beanName);
			Object result = factoryMethod.invoke(factoryBean, args);
			if (result == null && beanDefinition.isSingleton()) {
				throw new BeanCreationException(beanName,
						"Factory method '" + factoryMethod.getName() + "' returned null");
			}
			return result;
		}
		catch (InvocationTargetException ex) {
			throw new BeanCreationException(beanName,
					"Invocation of factory method '" + factoryMethod.getName() + "' failed", ex.getTargetException());
		}
		catch (IllegalAccessException ex) {
			throw new BeanCreationException(beanName,
					"Factory method '" + factoryMethod.getName() + "' is not accessible", ex);
		}
		finally {
			FactoryMethodInProgress.end();
		}
	}

	private Method findFactoryMethod(String beanName, Class<?> factoryClass, String methodName) {
		for (Method method : factoryClass.getMethods()) {
			if (method.getName().equals(methodName) && !method.isBridge()) {
				return method;
			}
		}
		for (Method method : factoryClass.getDeclaredMethods()) {
			if (method.getName().equals(methodName)) {
				method.setAccessible(true);
				return method;
			}
		}
		throw new BeanCreationException(beanName,
				"No factory method named '" + methodName + "' on " + factoryClass.getName());
	}

	private Object[] resolveFactoryMethodArguments(String beanName, Method factoryMethod) {
		Class<?>[] parameterTypes = factoryMethod.getParameterTypes();
		Object[] args = new Object[parameterTypes.length];
		for (int i = 0; i < parameterTypes.length; i++) {
			String[] candidates = getBeanNamesForType(parameterTypes[i]);
			if (candidates.length != 1) {
				throw new BeanCreationException(beanName,
						"Factory method '" + factoryMethod.getName() + "' parameter " + i + " of type ["
								+ parameterTypes[i].getName() + "] has " + candidates.length
								+ " candidates, expected exactly 1");
			}
			args[i] = getBean(candidates[0], parameterTypes[i]);
		}
		return args;
	}

	/**
	 * 若这个 Bean 需要销毁回调，就把它包装成适配器登记到单例池。
	 *
	 * <p>
	 * 「需要」的三种来源：实现了 {@code DisposableBean}、图纸上有 {@code destroy-method}、 或有
	 * {@code DestructionAwareBeanPostProcessor} 声明参与（比如 {@code @PreDestroy}）。 三者都没有的 Bean
	 * 不会被登记——所以销毁表通常远小于单例表。
	 * @param beanName Bean 名字
	 * @param bean 最终对象（可能已被 after 后处理器替换成代理）
	 * @param beanDefinition 图纸
	 */
	private void registerDisposableBeanIfNecessary(String beanName, Object bean, BeanDefinition beanDefinition) {
		String destroyMethodName = beanDefinition.getDestroyMethodName();
		if (DisposableBeanAdapter.requiresDestruction(bean, beanName, this.beanPostProcessors, destroyMethodName)) {
			registerDisposableBean(beanName,
					new DisposableBeanAdapter(bean, beanName, this.beanPostProcessors, destroyMethodName));
		}
	}

	/**
	 * 把图纸里的属性指令写进<b>已经造好</b>的对象。
	 * @param beanName Bean 名字
	 * @param beanDefinition 这张图纸
	 * @param bean 已经造好的对象
	 */
	protected void populateBean(String beanName, BeanDefinition beanDefinition, Object bean) {
		// 第 11 章：注解注入住在这里。注意**不能**因为图纸上没有属性就提前返回——
		// @Autowired 的字段与 XML 的 <property> 是两条完全独立的路（真实 Spring :1448-1470 同款）。
		PropertyValues propertyValues = beanDefinition.getPropertyValues();
		for (BeanPostProcessor processor : this.beanPostProcessors) {
			if (processor instanceof InstantiationAwareBeanPostProcessor instantiationAware) {
				PropertyValues current = instantiationAware.postProcessProperties(propertyValues, bean, beanName);
				if (current != null) {
					propertyValues = current;
				}
			}
		}
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
	/**
	 * 一个 Bean 的「初始化期」全部动作：before 回调 → 初始化方法 → after 回调。
	 *
	 * <p>
	 * 三个动作是一根<b>链</b>而不是三件独立的事：每一环的输出是下一环的输入，所以 after 阶段可以把对象整个换掉。 这正是第 26 章 AOP
	 * 代理的诞生方式。真实 Spring 在
	 * {@code AbstractAutowireCapableBeanFactory#initializeBean}（:1811）。
	 * @param beanName Bean 名字
	 * @param bean 已完成属性填充（含注解注入）的对象
	 * @return 最终对象（可能已被某个后处理器替换）
	 */
	private Object initializeBeanInternal(String beanName, Object bean, BeanDefinition beanDefinition) {
		invokeAwareMethods(beanName, bean);
		Object wrappedBean = applyBeanPostProcessorsBeforeInitialization(bean, beanName);
		invokeInitMethods(beanName, wrappedBean, beanDefinition);
		return applyBeanPostProcessorsAfterInitialization(wrappedBean, beanName);
	}

	/**
	 * 把「容器设施」递给 Bean：名字、类加载器、工厂。
	 *
	 * <p>
	 * 注意它在<b>属性填充之后</b>、在任何后处理器之前。这个位置是本章结论的一半： 依赖（协作对象）走属性填充，设施（容器自己）走这里，两者都有值之后才进初始化回调。
	 *
	 * <p>
	 * 真实 Spring 同样在 {@code initializeBean}
	 * 开头调它（{@code AbstractAutowireCapableBeanFactory} :1812，方法体 :1833），且先判一次
	 * {@code bean instanceof Aware} 再分三种。 我们砍掉了
	 * {@code BeanClassLoaderAware}（本书没有可切换的类加载器）。
	 * @param beanName Bean 名字
	 * @param bean 已完成属性填充的对象
	 */
	private void invokeAwareMethods(String beanName, Object bean) {
		if (bean instanceof Aware) {
			if (bean instanceof BeanNameAware beanNameAware) {
				beanNameAware.setBeanName(beanName);
			}
			if (bean instanceof BeanFactoryAware beanFactoryAware) {
				beanFactoryAware.setBeanFactory(this);
			}
		}
	}

	/**
	 * 依次调用所有后处理器的 before 方法。
	 *
	 * <p>
	 * 语义只有两条：<b>按注册顺序</b>逐个调；某个返回 {@code null} 就<b>终止整条链</b>并保留上一个结果 （而不是把 {@code null}
	 * 传下去）。真实 Spring：{@code :424}。
	 * @param existingBean 已经填好属性的对象
	 * @param beanName Bean 名字
	 * @return 链末端的对象
	 */
	private Object applyBeanPostProcessorsBeforeInitialization(Object existingBean, String beanName) {
		Object result = existingBean;
		for (BeanPostProcessor processor : this.beanPostProcessors) {
			Object current = processor.postProcessBeforeInitialization(result, beanName);
			if (current == null) {
				return result;
			}
			result = current;
		}
		return result;
	}

	/**
	 * 依次调用所有后处理器的 after 方法。语义同上。
	 *
	 * <p>
	 * 这一环的返回值<b>就是</b>最终进单例池的对象——第 28 章的「自调用失效」就源于此：调用方终于拿到代理了， 而 Bean <b>内部</b>的
	 * {@code this} 仍然是原始对象。
	 * @param existingBean 已完成初始化的对象
	 * @param beanName Bean 名字
	 * @return 链末端的对象（通常是一个代理）
	 */
	private Object applyBeanPostProcessorsAfterInitialization(Object existingBean, String beanName) {
		Object result = existingBean;
		for (BeanPostProcessor processor : this.beanPostProcessors) {
			Object current = processor.postProcessAfterInitialization(result, beanName);
			if (current == null) {
				return result;
			}
			result = current;
		}
		return result;
	}

	/**
	 * 初始化期的两条「方法通道」：{@code afterPropertiesSet()} 与 {@code init-method}。
	 *
	 * <p>
	 * 第三条通道（{@code @PostConstruct}）不在这里——它由
	 * {@code InitDestroyAnnotationBeanPostProcessor} 在 before 链里完成，而 before
	 * 链在<b>本方法之前</b>执行。所以三条通道的顺序是：
	 *
	 * <pre>
	 * &#64;PostConstruct（后处理器） → afterPropertiesSet（本方法） → init-method（本方法）
	 * </pre>
	 *
	 * <p>
	 * 最容易被忽略的是那个 {@code "afterPropertiesSet".equals(initMethodName)} 判断：如果配置里写了
	 * {@code init-method="afterPropertiesSet"}，不判一下就会调两次。真实 Spring
	 * （{@code AbstractAutowireCapableBeanFactory.invokeInitMethods} :1863-1886）有同款判断。
	 * @param beanName Bean 名字
	 * @param bean 已经填好属性、跑完 before 链的对象
	 * @param beanDefinition 图纸（可能为 {@code null}，比如手工 {@code initializeBean} 的调用）
	 */
	protected void invokeInitMethods(String beanName, Object bean, BeanDefinition beanDefinition) {
		boolean isInitializingBean = (bean instanceof InitializingBean);
		if (isInitializingBean) {
			try {
				((InitializingBean) bean).afterPropertiesSet();
			}
			catch (Exception ex) {
				throw new BeanCreationException(beanName,
						"Invocation of afterPropertiesSet of bean '" + beanName + "' failed", ex);
			}
		}
		if (beanDefinition == null) {
			return;
		}
		String initMethodName = beanDefinition.getInitMethodName();
		if (initMethodName == null || initMethodName.isEmpty()) {
			return;
		}
		if (isInitializingBean && "afterPropertiesSet".equals(initMethodName)) {
			return;
		}
		invokeCustomInitMethod(beanName, bean, initMethodName);
	}

	private void invokeCustomInitMethod(String beanName, Object bean, String initMethodName) {
		Method initMethod = findNoArgMethod(bean.getClass(), initMethodName);
		if (initMethod == null) {
			// 真实 Spring 抛 BeanDefinitionValidationException（配置错就是配置错），本书统一成
			// BeanCreationException，让「创建失败」只有一种异常可捕。
			throw new BeanCreationException(beanName, "Could not find an init method named '" + initMethodName
					+ "' on bean with name '" + beanName + "'");
		}
		try {
			initMethod.setAccessible(true);
			initMethod.invoke(bean);
		}
		catch (InvocationTargetException ex) {
			throw new BeanCreationException(beanName,
					"Invocation of init method '" + initMethodName + "' of bean '" + beanName + "' failed",
					ex.getTargetException());
		}
		catch (ReflectiveOperationException ex) {
			throw new BeanCreationException(beanName,
					"Could not invoke init method '" + initMethodName + "' of bean '" + beanName + "'", ex);
		}
	}

	/**
	 * 沿类层次找一个无参方法（{@code init-method} 只能是 public 或非 public 的无参方法）。
	 * @param beanClass 从哪个类开始找
	 * @param methodName 方法名
	 * @return 找到的方法，没有则 {@code null}
	 */
	private Method findNoArgMethod(Class<?> beanClass, String methodName) {
		for (Class<?> clazz = beanClass; clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
			try {
				return clazz.getDeclaredMethod(methodName);
			}
			catch (NoSuchMethodException ignored) {
				// 继续往父类找
			}
		}
		return null;
	}

	@Override
	public void addEmbeddedValueResolver(StringValueResolver valueResolver) {
		this.embeddedValueResolvers.add(valueResolver);
	}

	@Override
	public String resolveEmbeddedValue(String value) {
		if (value == null) {
			return null;
		}
		String result = value;
		for (StringValueResolver resolver : this.embeddedValueResolvers) {
			result = resolver.resolveStringValue(result);
		}
		return result;
	}

	/**
	 * 类型转换器。
	 * <p>
	 * 真实 Spring 在 {@code AbstractBeanFactory#getTypeConverter}（:1236），返回的是可在
	 * {@code ConfigurableBeanFactory#setTypeConverter} 里替换的策略对象。
	 * @return 转换器
	 */
	public SimpleTypeConverter getTypeConverter() {
		return this.typeConverter;
	}

	@Override
	public void setConversionService(ConversionService conversionService) {
		if (conversionService == null) {
			throw new IllegalArgumentException("ConversionService must not be null");
		}
		this.typeConverter = new SimpleTypeConverter(conversionService);
	}

	@Override
	public ConversionService getConversionService() {
		return this.typeConverter.getConversionService();
	}

}
