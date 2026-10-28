package org.springframework.beans.factory.annotation;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.NoSuchBeanException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

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
 * {@code ConcurrentHashMap}（本书每次扫一遍，见 3.4 节的限制）；按类型挑候选的完整决策链在
 * {@code DefaultListableBeanFactory#doResolveDependency}（第 19 章的「🔍 真实 Spring」逐行对照）。
 *
 * <p>
 * <b>第 20 章的改动</b>：入口参数从「{@code Field} + 注解」换成 {@link DependencyDescriptor}，
 * 并多了一条平行的值路线（{@code @Value}）。决策链本身<b>一行没改</b>——这正是把元数据抽出来 的回报：改的是「输入的形状」，不是「判断的逻辑」。
 *
 * @see org.springframework.beans.factory.annotation.Autowired
 * @see org.springframework.beans.factory.annotation.Value
 * @see org.springframework.beans.factory.config.DependencyDescriptor
 * <p>
 * <b>第 19 章的改动</b>：这个类第一次在<b>对象造出来之前</b>说话——{@link #determineCandidateConstructors}
 * 回答「用哪个构造器」，容器再按那个构造器的参数逐个向自己要 Bean。所以本章的构造器注入不是「属性填充的一个变种」，
 * 而是把装配<b>提前到了实例化那一刻</b>；也正因此，走构造器注入的依赖不需要、也无法再被字段注入覆盖。
 * @see org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor
 */
public class AutowiredAnnotationBeanPostProcessor
		implements SmartInstantiationAwareBeanPostProcessor, BeanFactoryAware {

	private ConfigurableListableBeanFactory beanFactory;

	/**
	 * 构造器需要一个<b>能按类型枚举</b>的工厂：按类型找候选要遍历图纸与单例两边， 那是 {@link ListableBeanFactory} 才有的能力（第 06
	 * 章）。
	 * <p>
	 * 真实 Spring 不用构造器参数，而是实现 {@code BeanFactoryAware} 让容器回注（第 12 章）—— 因为写成 XML 的 Bean
	 * 没法传构造器参数给自己。
	 * @param beanFactory 用来解析依赖的工厂
	 */
	public AutowiredAnnotationBeanPostProcessor() {
	}

	/**
	 * 便利构造器：手工 {@code new} 的时候可以一次给全（本书的测试大量用到它）。
	 * @param beanFactory 用来解析依赖的工厂
	 */
	public AutowiredAnnotationBeanPostProcessor(ConfigurableListableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		if (!(beanFactory instanceof ConfigurableListableBeanFactory configurableBeanFactory)) {
			throw new IllegalArgumentException(
					"AutowiredAnnotationBeanPostProcessor requires a ConfigurableListableBeanFactory but got: "
							+ beanFactory);
		}
		this.beanFactory = configurableBeanFactory;
	}

	/**
	 * <b>本章新增的那次发言</b>：在对象还不存在的时候，回答「用哪个构造器」。
	 *
	 * <p>
	 * 这是全书唯一<b>不</b>发生在「造完对象之后」的回调。其它回调都要先有对象，而它拿到的只有一张图纸和一个 {@code Class}——所以它不在
	 * {@link org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor}
	 * 上，而在更早的 {@link SmartInstantiationAwareBeanPostProcessor} 上（第 19 章新增的那个最小接口）。
	 *
	 * <p>
	 * 语义逐条对齐真实 Spring 的 {@code determineCandidateConstructors}（{@code :365}）：
	 * <ol>
	 * <li>扫全部构造器，挑出带 {@code @Autowired} 的；
	 * <li>带 {@code required=true} 的构造器最多一个，且不能与别的带注解构造器并存，否则抛
	 * {@link BeanCreationException}（两条消息与真实 Spring {@code :415} / {@code :423} 一字不差）；
	 * <li>有带注解的构造器就<b>只用</b>它们；{@code required=false} 的那条凑不齐时允许退回无参构造器， 所以把无参构造器垫在候选末尾；
	 * <li>没有带注解的构造器、但类只有<b>一个带参构造器</b>——没得选，就是它（真实 Spring 的「单构造器自动选中」）；
	 * <li>其余情况返回 {@code null}：多个构造器一个都没标，或只有一个无参构造器。
	 * </ol>
	 * 第 5 条在本仓的落地与真实 Spring 有一处<b>已知差异</b>，写在正文里而不是藏起来：真实 Spring 拿到 {@code null} 之后走
	 * {@code instantiateBean}（只认无参构造器），本仓拿到 {@code null} 之后走第 04 章的
	 * {@link org.springframework.beans.factory.support.ConstructorResolver}（参数多的优先）。第 04
	 * 章的规矩不动， 它的测试在 {@code ConstructorInjectionTests} 里钉着。
	 * @param beanClass 要造的类
	 * @param beanName Bean 名字，只用于异常消息
	 * @return 候选构造器；{@code null} 表示「我不管，按第 04 章的规矩挑」
	 */
	@Override
	public Constructor<?>[] determineCandidateConstructors(Class<?> beanClass, String beanName) {
		Constructor<?>[] rawCandidates = beanClass.getDeclaredConstructors();
		List<Constructor<?>> candidates = new ArrayList<>(rawCandidates.length);
		Constructor<?> requiredConstructor = null;
		Constructor<?> defaultConstructor = null;
		for (Constructor<?> candidate : rawCandidates) {
			Autowired autowired = candidate.getAnnotation(Autowired.class);
			if (autowired != null) {
				candidates.add(candidate);
				if (autowired.required()) {
					requiredConstructor = requireSingle(candidates, requiredConstructor, candidate, beanName);
				}
			}
			else if (candidate.getParameterCount() == 0) {
				defaultConstructor = candidate;
			}
		}
		if (!candidates.isEmpty()) {
			return withOptionalFallback(candidates, requiredConstructor, defaultConstructor);
		}
		if (rawCandidates.length == 1 && rawCandidates[0].getParameterCount() > 0) {
			return rawCandidates;
		}
		return null;
	}

	/**
	 * 校验「带 {@code required=true} 的构造器最多一个」，并把那个构造器记下来。
	 *
	 * <p>
	 * 两条消息都点名<b>冲突的那两个构造器</b>——「哪个类里有两个 {@code @Autowired} 构造器」正是用户唯一想知道的事。
	 * @param candidates 目前收集到的带注解构造器（已含 {@code candidate}）
	 * @param requiredConstructor 之前遇到的 {@code required=true} 构造器，没有则为 {@code null}
	 * @param candidate 当前这个构造器
	 * @param beanName Bean 名字
	 * @return 应当记为「唯一必需构造器」的那个
	 */
	private Constructor<?> requireSingle(List<Constructor<?>> candidates, Constructor<?> requiredConstructor,
			Constructor<?> candidate, String beanName) {
		if (requiredConstructor != null) {
			throw new BeanCreationException(beanName, "Invalid autowire-marked constructor: " + candidate
					+ ". Found constructor with 'required' Autowired annotation already: " + requiredConstructor);
		}
		if (candidates.size() > 1) {
			throw new BeanCreationException(beanName, "Invalid autowire-marked constructors: " + candidates
					+ ". Found constructor with 'required' Autowired annotation: " + candidate);
		}
		return candidate;
	}

	/**
	 * 把一条「退路」垫在候选末尾：必需构造器存在时不给退路（凑不齐就该报错，这正是 {@code required=true} 的含义），
	 * 只有可选构造器才允许退回无参构造器。
	 * @param candidates 带注解的构造器
	 * @param requiredConstructor 必需构造器，没有则为 {@code null}
	 * @param defaultConstructor 无参构造器，没有则为 {@code null}
	 * @return 候选构造器数组
	 */
	private Constructor<?>[] withOptionalFallback(List<Constructor<?>> candidates, Constructor<?> requiredConstructor,
			Constructor<?> defaultConstructor) {
		if (requiredConstructor == null && defaultConstructor != null) {
			candidates.add(defaultConstructor);
		}
		return candidates.toArray(new Constructor<?>[0]);
	}

	@Override
	public PropertyValues postProcessProperties(PropertyValues propertyValues, Object bean, String beanName) {
		if (this.beanFactory == null) {
			throw new BeanCreationException(beanName,
					"AutowiredAnnotationBeanPostProcessor has no BeanFactory while processing bean '" + beanName
							+ "': declare it as a bean (so that BeanFactoryAware can hand it one) or construct it with one");
		}
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
			DependencyDescriptor descriptor = descriptorFor(field);
			if (descriptor == null) {
				continue;
			}
			Object value;
			try {
				value = resolveDependency(beanName, descriptor);
			}
			catch (RuntimeException ex) {
				// 唯一的加壳地点：消息里必须同时有「哪个字段」和「哪个 Bean」。
				// 分散在两条路线里各包一次的话，Bean 路线（第 19 章写的）会漏掉字段名。
				throw new BeanCreationException(beanName, "Could not inject field '" + descriptor.getDependencyName()
						+ "' of bean '" + beanName + "' (declared in " + descriptor.getDeclaringClass().getName() + ")",
						ex);
			}
			if (value == null) {
				// 只有「可选依赖缺失」会走到这里（@Value 解析出来的永远是字符串或直接抛异常）
				continue;
			}
			writeField(beanName, bean, field, value);
		}
	}

	/**
	 * 把一个字段翻译成「依赖描述」；两种注解都没有时返回 {@code null}（表示跳过）。
	 *
	 * <p>
	 * 这个方法是第 20 章的<b>全部接口变化</b>：以前在循环体里现抠 {@code @Autowired}，
	 * 现在抠成对象。抠的事实有四个——类型、泛型、名字、是否必需——后面全程只认这四个。
	 *
	 * <p>
	 * {@code required} 的取值规则：只有 {@code @Value} 时恒为 {@code true}（文本解析失败就该炸，
	 * 没有「可选文本」这回事）；两个注解都在时以 {@code @Autowired} 的为准。
	 * @param field 字段
	 * @return 描述对象，或 {@code null}
	 */
	private DependencyDescriptor descriptorFor(Field field) {
		Autowired autowired = field.getAnnotation(Autowired.class);
		boolean annotatedWithValue = field.getAnnotation(Value.class) != null;
		if (autowired == null && !annotatedWithValue) {
			return null;
		}
		boolean required = (autowired == null) || autowired.required();
		return new DependencyDescriptor(field, required);
	}

	/**
	 * 解析一个注入点：<b>消歧决策链</b>就在这里。
	 *
	 * <p>
	 * 顺序（每一步只在前一步没把候选缩到 1 个时才生效）：
	 * <ol>
	 * <li>{@code @Qualifier} <b>硬过滤</b>——不是打分，是「只接受这个名字」
	 * <li>{@code @Primary}——提供方声明的默认实现
	 * <li>字段名匹配——候选名恰好等于字段名（<b>排在优先级之前</b>，见下文）
	 * <li>优先级（类上的 {@code @Order}）——数字小的胜，打平直接抛异常
	 * </ol>
	 * 四步都失败才抛 {@link NoUniqueBeanDefinitionException}，并把全部候选名列进消息。
	 * @param beanName 正在被注入的 Bean 名字
	 * @param descriptor 依赖描述（第 20 章新增的第一个参数）
	 * @return 解析到的值；可选依赖缺失时返回 {@code null}
	 */
	private Object resolveDependency(String beanName, DependencyDescriptor descriptor) {
		String suggestedValue = descriptor.getSuggestedValue();
		if (suggestedValue != null) {
			// 值路线：不查候选，只做「占位符替换 + 类型转换」
			return resolveValue(beanName, descriptor, suggestedValue);
		}
		// Bean 路线：第 19 章的消歧决策链
		return resolveBean(beanName, descriptor);
	}

	/**
	 * 值路线：{@code @Value} 的三步。
	 *
	 * <ol>
	 * <li><b>占位符替换</b>——交给工厂维护的解析器链（{@code resolveEmbeddedValue}）。注意这一步用的是 <b>工厂</b>而不是
	 * {@code Environment}：容器里 {@code ClassPathXmlApplicationContext} 与
	 * {@code GenericApplicationContext} 的 Environment 可以不同，但解析器链是容器级的。
	 * <li><b>类型转换</b>——{@code "8080"} 到 {@code int}，{@code "true"} 到 {@code boolean}。
	 * <li><b>失败要响</b>——转不了就抛 {@code TypeMismatchException}，由外层包成
	 * {@code BeanCreationException} 点名 Bean 与字段。
	 * </ol>
	 *
	 * <p>
	 * <b>这里有个必须说清的坑</b>：如果容器里<b>没有</b>装严格解析器，{@code "${app.missing}"} 会被原样返回，于是字段被塞进字符串
	 * {@code "${app.missing}"}——启动成功，运行期才发现是脏数据。 严格性不在 {@code @Value} 身上，在
	 * {@code PropertySourcesPlaceholderConfigurer} 身上 （见 4.3 节的真红）。
	 * @param beanName 正在被注入的 Bean 名字
	 * @param descriptor 依赖描述
	 * @param suggestedValue 注解原文
	 * @return 转换后的值
	 */
	private Object resolveValue(String beanName, DependencyDescriptor descriptor, String suggestedValue) {
		// 这里不再自己加壳：两步都可能抛（严格解析器在第一步就抛），
		// 加壳交给 injectFields 一处完成——否则两层消息里会重复同一段字段名。
		String resolved = this.beanFactory.resolveEmbeddedValue(suggestedValue);
		return this.beanFactory.getTypeConverter()
			.convert(resolved, descriptor.getDependencyType(), descriptor.getDependencyName(),
					descriptor.getDeclaringClass());
	}

	/**
	 * Bean 路线：第 19 章的消歧决策链，签名换成「只吃描述对象」。
	 * @param beanName 正在被注入的 Bean 名字
	 * @param descriptor 依赖描述
	 * @return 解析到的 Bean；所需缺失且非必需时 {@code null}
	 */
	private Object resolveBean(String beanName, DependencyDescriptor descriptor) {
		Class<?> type = descriptor.getDependencyType();
		String dependencyName = descriptor.getDependencyName();
		Class<?> elementType = elementTypeOf(beanName, descriptor, type);
		if (elementType != null) {
			return resolveCollection(elementType, List.class.isAssignableFrom(type));
		}
		List<String> candidates = new ArrayList<>(List.of(this.beanFactory.getBeanNamesForType(type)));
		String qualifier = qualifierOf(descriptor);
		if (qualifier != null) {
			// 硬过滤：被 @Qualifier 排除掉的候选，后面所有手段都不再考虑它。
			candidates.removeIf((candidate) -> !candidate.equals(qualifier));
			if (candidates.isEmpty()) {
				throw new NoSuchBeanException(
						"No bean of type [" + type.getName() + "] with qualifier '" + qualifier + "' is registered");
			}
		}
		if (candidates.isEmpty()) {
			if (!descriptor.isRequired()) {
				return null;
			}
			throw new NoSuchBeanException("No bean of type [" + type.getName() + "] is registered");
		}
		if (candidates.size() > 1) {
			candidates = narrowToPrimary(type, candidates);
		}
		if (candidates.size() > 1 && candidates.contains(dependencyName)) {
			// 真实 Spring 的顺序出人意料：名字匹配排在优先级<b>之前</b>
			// （determineAutowireCandidate 的 Step 2a/2b 在 Step 3 之前）。
			candidates = List.of(dependencyName);
		}
		if (candidates.size() > 1) {
			String best = highestPriority(type, candidates);
			if (best != null) {
				candidates = List.of(best);
			}
		}
		if (candidates.size() > 1) {
			throw new NoUniqueBeanDefinitionException(type, candidates);
		}
		return this.beanFactory.getBean(candidates.get(0));
	}

	/**
	 * 集合注入：{@code List<T>} 或 {@code Map<String,T>} 一次拿走全部候选。
	 * @param elementType 元素类型（从泛型参数里读出来的）
	 * @param asList {@code true} 返回 {@link List}，否则返回 {@link Map}
	 * @return 集合
	 */
	private Object resolveCollection(Class<?> elementType, boolean asList) {
		String[] names = this.beanFactory.getBeanNamesForType(elementType);
		if (asList) {
			List<Object> values = new ArrayList<>(names.length);
			for (String name : names) {
				values.add(this.beanFactory.getBean(name));
			}
			return values;
		}
		Map<String, Object> values = new LinkedHashMap<>();
		for (String name : names) {
			values.put(name, this.beanFactory.getBean(name));
		}
		return values;
	}

	/**
	 * 从字段的<b>泛型类型</b>里读出元素类型。{@code List} / {@code Map} 返回非 {@code null}。
	 * <p>
	 * 真实 Spring 用 {@code org.springframework.core.GenericTypeResolver}
	 * 做这件事（还处理嵌套泛型与类型变量）， 本书只处理「一层具体类型参数」。
	 * @param beanName Bean 名字，只用于异常消息
	 * @param descriptor 依赖描述
	 * @param rawType 字段的原始类型
	 * @return 元素类型；不是集合时返回 {@code null}
	 */
	private Class<?> elementTypeOf(String beanName, DependencyDescriptor descriptor, Class<?> rawType) {
		boolean map = Map.class.isAssignableFrom(rawType);
		if (!map && !List.class.isAssignableFrom(rawType)) {
			return null;
		}
		Type generic = descriptor.getGenericType();
		if (!(generic instanceof ParameterizedType parameterized)) {
			throw new BeanCreationException(beanName, "Could not autowire field: " + descriptor.getDependencyName()
					+ " (bean '" + beanName + "'): " + rawType.getName() + " needs a generic element type");
		}
		Type[] arguments = parameterized.getActualTypeArguments();
		Type candidate = map ? arguments[1] : arguments[0];
		if (candidate instanceof Class<?> clazz) {
			return clazz;
		}
		if (candidate instanceof ParameterizedType nested && nested.getRawType() instanceof Class<?> clazz) {
			return clazz;
		}
		throw new BeanCreationException(beanName, "Could not autowire field: " + descriptor.getDependencyName()
				+ " (bean '" + beanName + "'): unsupported element type " + candidate);
	}

	/**
	 * 第二步：图纸上的 {@code primary} 标记。恰好一个候选带它才收窄，多个带它等于没带（交给下一步）。
	 * @param type 想要的类型
	 * @param candidates 当前候选
	 * @return 收窄后的候选
	 */
	private List<String> narrowToPrimary(Class<?> type, List<String> candidates) {
		List<String> primaries = new ArrayList<>();
		for (String candidate : candidates) {
			if (isPrimary(candidate)) {
				primaries.add(candidate);
			}
		}
		return (primaries.size() == 1) ? primaries : candidates;
	}

	/**
	 * 这个候选在图纸上被标成「默认实现」了吗。
	 * <p>
	 * 注意这里读的是图纸上的布尔量，不是 {@code @Primary} 注解——注解属于注解层， 由扫描器与配置类解析器在注册时翻译过来（见
	 * {@code BeanDefinition#isPrimary()} 的说明）。
	 * @param beanName 候选名
	 * @return 是则为 {@code true}
	 */
	private boolean isPrimary(String beanName) {
		return this.beanFactory.containsBeanDefinition(beanName)
				&& this.beanFactory.getBeanDefinition(beanName).isPrimary();
	}

	/**
	 * 第四步：优先级。数字最小的胜；<b>两个候选优先级相同即抛异常</b>（真实 Spring 6.2 同款）。
	 * @param type 想要的类型
	 * @param candidates 当前候选
	 * @return 优先级最高的候选名；没有任何候选声明优先级时 {@code null}
	 */
	private String highestPriority(Class<?> type, List<String> candidates) {
		String best = null;
		Integer bestOrder = null;
		List<String> tied = new ArrayList<>();
		for (String candidate : candidates) {
			Integer order = orderOf(this.beanFactory.getType(candidate));
			if (order == null) {
				continue;
			}
			if (bestOrder == null || order < bestOrder) {
				bestOrder = order;
				best = candidate;
				tied.clear();
				tied.add(candidate);
			}
			else if (order.equals(bestOrder)) {
				tied.add(candidate);
			}
		}
		if (tied.size() > 1) {
			throw new NoUniqueBeanDefinitionException(type, tied);
		}
		return best;
	}

	/**
	 * 读类上的 {@code @Order}。本书只读注解；真实 Spring 还会读实现了 {@link Ordered} 的实例、
	 * {@code jakarta.annotation.Priority}，并通过 {@code OrderComparator#getPriority} 合成。
	 * @param beanClass 候选的类（可能为 {@code null}）
	 * @return 排序值；没声明时 {@code null}（<b>不是</b> {@link Ordered#LOWEST_PRECEDENCE}）
	 */
	private Integer orderOf(Class<?> beanClass) {
		if (beanClass == null) {
			return null;
		}
		Order order = beanClass.getAnnotation(Order.class);
		return (order != null) ? order.value() : null;
	}

	/**
	 * 读字段上的 {@code @Qualifier}。
	 * @param descriptor 依赖描述
	 * @return 限定名；没有注解时 {@code null}
	 */
	private String qualifierOf(DependencyDescriptor descriptor) {
		Qualifier qualifier = descriptor.getField().getAnnotation(Qualifier.class);
		return (qualifier != null) ? qualifier.value() : null;
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
