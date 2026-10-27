package org.springframework.beans.factory.config;

import org.springframework.beans.factory.HierarchicalBeanFactory;
import org.springframework.beans.SimpleTypeConverter;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.ParameterNameDiscoverer;

/**
 * 加一层：**这些设置可以改**。
 *
 * <p>
 * 「Configurable」这个词在这里的含义很具体：前几层的接口全是<b>读</b>的，这一层开始有<b>写</b>。 真实 Spring 的这一层有 35
 * 个方法，绝大多数是「设置某个策略」：类加载器、表达式解析器、 类型转换服务、属性编辑器、作用域、别名、依赖记录、销毁……
 *
 * <p>
 * 本书只留三个，因为它们是前十章真的用到过的：
 * <ul>
 * <li>{@link #setParentBeanFactory}：父子容器不是天生就有的，得有人接上；
 * <li>{@link #setParameterNameDiscoverer}：第 04 章的构造器注入依赖它；
 * <li>{@link #addBeanPostProcessor}：第 11 章的扩展点必须能装进来，否则整章无从谈起。
 * </ul>
 *
 * <p>
 * 此外它继承 {@link SingletonBeanRegistry}，因为「直接注册一个现成对象」也是一次<b>写</b>操作。 真实 Spring
 * 的写法完全一样：{@code ConfigurableBeanFactory extends HierarchicalBeanFactory, SingletonBeanRegistry}。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.beans.factory.config.ConfigurableBeanFactory}（436 行）。
 *
 * @see AutowireCapableBeanFactory
 */
public interface ConfigurableBeanFactory extends HierarchicalBeanFactory, SingletonBeanRegistry {

	/**
	 * 注册一个自定义作用域。
	 * @param scopeName 作用域名（图纸上 {@code scope="thread"} 用的就是它）
	 * @param scope 作用域实现
	 */
	void registerScope(String scopeName, Scope scope);

	/**
	 * 查已注册的作用域。
	 * @param scopeName 作用域名
	 * @return 作用域实现，没有则 {@code null}
	 */
	Scope getRegisteredScope(String scopeName);

	/**
	 * 列出所有已注册的作用域名。
	 * @return 作用域名数组
	 */
	String[] getRegisteredScopeNames();

	/**
	 * 接上一个父容器。
	 * @param parentBeanFactory 父容器
	 */
	void setParentBeanFactory(org.springframework.beans.factory.BeanFactory parentBeanFactory);

	/**
	 * 换掉「参数叫什么名字」的策略。
	 * @param parameterNameDiscoverer 新的策略
	 */
	void setParameterNameDiscoverer(ParameterNameDiscoverer parameterNameDiscoverer);

	/**
	 * 当前的参数名策略。
	 * @return 参数名策略
	 */
	ParameterNameDiscoverer getParameterNameDiscoverer();

	/**
	 * 装上一个后处理器。**顺序就是调用顺序**。
	 * @param beanPostProcessor 要装的后处理器
	 */
	void addBeanPostProcessor(BeanPostProcessor beanPostProcessor);

	/**
	 * 已经装了几个后处理器。
	 *
	 * <p>
	 * 真实 Spring 有这个方法（:271），存在的意义和 {@code getBeanDefinitionCount()} 一样： 让容器内部的装配状态可被观察。
	 * @return 后处理器数量
	 */
	int getBeanPostProcessorCount();

	/**
	 * 装上一个「文本解析器」。**顺序就是调用顺序**，先装的先问。
	 *
	 * <p>
	 * 名字里的 {@code Embedded}（内嵌）指的就是「文本里内嵌着 {@code ${...}}」这种形态—— 与「整条文本就是一个占位符」相区分（后者在真实
	 * Spring 里走 {@code PropertySourcesPlaceholderConfigurer#processProperties} 的严格分支）。
	 *
	 * <p>
	 * 真实 Spring 这个方法在 {@code AbstractBeanFactory#addEmbeddedValueResolver}（:276）， 内部就是一个
	 * {@code CopyOnWriteArrayList}——注意是 <b>List 不是单值</b>： 允许叠加，多个解析器依次生效。
	 * @param valueResolver 解析器
	 */
	void addEmbeddedValueResolver(StringValueResolver valueResolver);

	/**
	 * 把一个文本交给所有解析器依次处理。
	 *
	 * <p>
	 * 三个实现细节值得记住：
	 * <ol>
	 * <li>列表为空时<b>原样返回</b>，不是返回 {@code null}——否则所有没装解析器的容器里，字面量 {@code @Value("Hello")}
	 * 都会变成 {@code null}；
	 * <li>每个解析器的返回值成为下一个的输入（链式，不是各自独立跑一遍）；
	 * <li>解析器不允许返回 {@code null}（见 {@link StringValueResolver} 的契约）。
	 * </ol>
	 *
	 * <p>
	 * 真实 Spring 在 {@code AbstractBeanFactory#resolveEmbeddedValue}（:282），代码与上面三点一一对应。
	 * @param value 原始文本，可以为 {@code null}
	 * @return 处理后的文本；入参为 {@code null} 时返回 {@code null}
	 */
	String resolveEmbeddedValue(String value);

	/**
	 * 类型转换器——把 {@code "8080"} 变成 {@code 8080} 的那个东西。
	 *
	 * <p>
	 * 真实 Spring 声明在 {@code ConfigurableBeanFactory}（:491），返回类型是接口
	 * {@code TypeConverter}，并且配了一个 {@code setTypeConverter} 可以整只换掉； 本书返回具体类
	 * {@link SimpleTypeConverter}，换的是它背后的 {@link ConversionService}（见
	 * {@link #setConversionService}）。
	 * @return 转换器
	 */
	SimpleTypeConverter getTypeConverter();

	/**
	 * 换掉容器用的转换服务。第 23 章起容器里的 {@code @Value} 注入、属性填充都走这个服务， 所以在这里塞进一个带自定义
	 * {@code Converter} 的服务，等于给整个容器加了新的转换规则。
	 *
	 * <p>
	 * 真实 Spring 的 {@code setConversionService} 在
	 * {@code ConfigurableBeanFactory}（:509），语义一致； 差别是它默认 {@code null}（此时转换走
	 * {@code PropertyEditor}），而本书默认就有一个
	 * {@link org.springframework.core.convert.support.DefaultConversionService}。
	 * @param conversionService 转换服务
	 */
	void setConversionService(ConversionService conversionService);

	/**
	 * 当前转换服务。
	 * @return 转换服务，永不为 {@code null}
	 */
	ConversionService getConversionService();

}
