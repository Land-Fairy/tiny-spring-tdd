package org.springframework.context.support;

import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 把 {@code refresh()} 的骨架立起来的抽象上下文。
 *
 * <p>
 * 本章的全部内容就是下面这个方法里的<b>十二行调用</b>。它们本身不做事——每个步骤都是一个可覆写的 {@code protected}
 * 方法，本章只填了其中两步（准备状态、造单例），其余九步是空的，留到后面各章。 <b>但顺序是完整的、也是不能改的</b>：后面每章只需要往对应的格子里塞实现，不用再动骨架。
 *
 * <p>
 * 为什么「先立骨架、再填格子」比「一章实现一步、最后拼起来」更好？因为骨架本身就是知识：
 * 「注册后处理器必须早于造单例」「造单例必须早于发事件」这类约束，只有在能一眼看到全部十二步时才看得出来。 分成十二次实现，每一步都显得可以随便放。
 *
 * <p>
 * 两个状态位（{@code active} / {@code closed}）用 {@link AtomicBoolean} 而不是 {@code boolean}：
 * 它们会被「启动线程」写、「业务线程」读，而 {@code volatile boolean} 无法保证「检查并报错」这一组合的语义。 用原子类不是为了在这里做
 * CAS，而是为了让<b>可见性</b>这件事无需讨论。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.context.support.AbstractApplicationContext} （1653
 * 行）。本书约 400 行。砍掉的都在 {@code prepareBeanFactory}（约 30 行的环境准备）与
 * {@code finishBeanFactoryInitialization}（conversion service、load-time weaver、
 * {@code freezeConfiguration}）里，另加 {@code startupShutdownLock} 这层并发保护（第 31 章）。
 *
 * @see GenericApplicationContext
 */
public abstract class AbstractApplicationContext implements ConfigurableApplicationContext {

	private final String displayName;

	private final AtomicBoolean active = new AtomicBoolean();

	private final AtomicBoolean closed = new AtomicBoolean();

	private volatile long startupDate;

	private final StandardEnvironment environment = new StandardEnvironment();

	private final List<BeanFactoryPostProcessor> beanFactoryPostProcessors = new CopyOnWriteArrayList<>();

	/**
	 * 子类通过它给上下文起个名字——名字会进异常消息。
	 * @param displayName 显示名
	 */
	protected AbstractApplicationContext(String displayName) {
		this.displayName = displayName;
	}

	// ---------------------------------------------------------------------
	// 本章的主角：refresh()
	// ---------------------------------------------------------------------

	@Override
	public void refresh() throws IllegalStateException {
		// 1. 把状态切成「启动中」，记下启动时刻
		prepareRefresh();

		// 2. 让子类准备（或刷新）自己的 BeanFactory
		ConfigurableListableBeanFactory beanFactory = obtainFreshBeanFactory();

		// 3. 给 BeanFactory 补上容器级的装备（真实 Spring 在这里做 30 行准备工作）
		prepareBeanFactory(beanFactory);

		try {
			// 4. 子类可以在这时修改 BeanFactory（Web 容器在这里注册 request/session 作用域）
			postProcessBeanFactory(beanFactory);

			// 5. 调用 BeanFactoryPostProcessor —— 第 10 章填
			invokeBeanFactoryPostProcessors(beanFactory);

			// 6. 注册 BeanPostProcessor —— 第 11 章填
			registerBeanPostProcessors(beanFactory);

			// 7. 初始化消息源（国际化）—— 本书不实现，Out of Scope
			initMessageSource();

			// 8. 初始化事件广播器 —— 第 15 章填
			initApplicationEventMulticaster();

			// 9. 子类自己的特殊 Bean（Spring MVC 在这里初始化 HandlerMapping）
			onRefresh();

			// 10. 把监听器 Bean 注册到广播器 —— 第 15 章填
			registerListeners();

			// 11. 造出所有非懒加载的单例
			finishBeanFactoryInitialization(beanFactory);

			// 12. 收尾：发布 ContextRefreshedEvent —— 第 15 章填
			finishRefresh();
		}
		catch (RuntimeException ex) {
			// 启动失败必须「收拾干净」：已经造好的单例要销毁，否则文件句柄、线程池、
			// 数据库连接会随着一次失败的启动泄漏出去，而且没人知道它们存在。
			destroyBeans();
			cancelRefresh(ex);
			throw ex;
		}
	}

	/**
	 * 第一步：把上下文切成「已激活」，并初始化启动时刻。
	 * <p>
	 * 真实 Spring 在这里还做了：初始化占位符属性源、校验必需属性、备份监听器列表、
	 * 准备早期事件集合（{@code :674-710}）。本书只做状态位与时间戳——那三件事依赖 Environment（第 10 章）与事件（第 15 章）。
	 */
	protected void prepareRefresh() {
		this.startupDate = System.currentTimeMillis();
		this.closed.set(false);
		this.active.set(true);
	}

	/**
	 * 第二步：拿到（或刷新出）一个干净的 BeanFactory。
	 * <p>
	 * 它只有两行，但这两行是一条<b>契约</b>：先 {@link #refreshBeanFactory()} 让子类准备好内部工厂， 再
	 * {@link #getBeanFactory()} 取出来用。子类因此可以在「准备」里做任何事（读 XML、注册内置 Bean）， 而骨架不需要知道细节。
	 * @return 可配置的可列表化 BeanFactory
	 */
	protected ConfigurableListableBeanFactory obtainFreshBeanFactory() {
		refreshBeanFactory();
		return getBeanFactory();
	}

	/**
	 * 第三步：给 BeanFactory 补容器级装备。
	 * <p>
	 * 真实 Spring 在这里做了三十多行（{@code :726-782}）：设置 ClassLoader、注册 {@code Environment} 与
	 * {@code EmbeddedValueResolver}、注册 {@code ApplicationContextAwareProcessor}、忽略一批
	 * Aware 接口的自动装配、 注册 {@code resolvableDependencies}（{@code BeanFactory}
	 * 自己可以被注入就是这个原因）、 添加 {@code ApplicationListenerDetector}。
	 * <p>
	 * 本书这里什么都不做，但<b>保留这个方法</b>——它在真实 Spring 里是「上下文与工厂的分界线」， 所有「上下文才知道的东西」都在这一步灌进工厂。第 10
	 * 章会回来填一行：注入 Environment。
	 * @param beanFactory 目标工厂
	 */
	protected void prepareBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		// 本书：暂无容器级装备可补（第 10 章起才有）
	}

	/**
	 * 第四步：留给子类的第一个钩子。
	 * @param beanFactory 目标工厂
	 */
	protected void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		// 子类覆写
	}

	/**
	 * 第五步：调用 {@code BeanFactoryPostProcessor}。<b>第 10 章填。</b>
	 * <p>
	 * 它必须早于造单例，因为这些后处理器改的是<b>图纸</b>（比如把占位符换成真值）， 图纸改完再按图纸造对象才正确。
	 * @param beanFactory 目标工厂
	 */
	protected void invokeBeanFactoryPostProcessors(ConfigurableListableBeanFactory beanFactory) {
		// 先跑程序式登记的（最早期，通常是容器自己或测试塞进来的）
		for (BeanFactoryPostProcessor postProcessor : this.beanFactoryPostProcessors) {
			postProcessor.postProcessBeanFactory(beanFactory);
		}
		// 再跑写成 Bean 的那些：按注册顺序，逐个实例化后调用。
		// getBeanNamesForType 替我们做完了「哪些定义是后处理器」这件事——
		// 这正是第 06 章把 ListableBeanFactory 拆出来的收益。
		for (String beanName : beanFactory.getBeanNamesForType(BeanFactoryPostProcessor.class)) {
			beanFactory.getBean(beanName, BeanFactoryPostProcessor.class).postProcessBeanFactory(beanFactory);
		}
	}

	/**
	 * 第六步：注册 {@code BeanPostProcessor}。<b>第 11 章填。</b>
	 * <p>
	 * 它必须晚于 {@code invokeBeanFactoryPostProcessors}（否则后处理器 Bean 自己还没被处理）， 又必须早于
	 * {@code finishBeanFactoryInitialization}（否则拦不到任何一次创建）。 <b>这个「卡在中间」的位置就是它存在的意义。</b>
	 * <p>
	 * 实现只有三行，但它解释了「为什么后处理器必须写成 Bean」：容器<b>没有任何别的办法</b>知道 该装哪些后处理器——它只能按类型去问自己。真实 Spring
	 * 把这三行做成了 544 行的 {@code PostProcessorRegistrationDelegate}，多出来的部分全在处理
	 * {@code PriorityOrdered} 与 {@code Ordered} 的排序（第 16 章）、以及内部后处理器的固定位置。
	 * @param beanFactory 目标工厂
	 */
	protected void registerBeanPostProcessors(ConfigurableListableBeanFactory beanFactory) {
		for (String beanName : beanFactory.getBeanNamesForType(BeanPostProcessor.class)) {
			beanFactory.addBeanPostProcessor(beanFactory.getBean(beanName, BeanPostProcessor.class));
		}
	}

	/**
	 * 第七步：初始化消息源。本书不实现（国际化在 Out of Scope 里）。
	 */
	protected void initMessageSource() {
		// 本书不实现
	}

	/**
	 * 第八步：初始化事件广播器。<b>第 15 章填。</b>
	 */
	protected void initApplicationEventMulticaster() {
		// 第 15 章
	}

	/**
	 * 第九步：留给子类的第二个钩子——「在造单例之前，你还有什么要做的」。
	 */
	protected void onRefresh() {
		// 子类覆写
	}

	/**
	 * 第十步：把监听器 Bean 注册到广播器。<b>第 15 章填。</b>
	 */
	protected void registerListeners() {
		// 第 15 章
	}

	/**
	 * 第十一步：造出所有非懒加载的单例——<b>本章唯一真正干活的步骤</b>。
	 * <p>
	 * 真实 Spring 在这里先做五件准备（{@code :939-991}）：启动 bootstrap executor、 设置
	 * {@code ConversionService}、注册默认 {@code EmbeddedValueResolver}、 提前初始化
	 * {@code LoadTimeWeaverAware}、{@code freezeConfiguration()} 冻结图纸。 最后才是
	 * {@code preInstantiateSingletons()}。
	 * <p>
	 * 本书只有最后一行，但读者的收获应该是：<b>「启动时把东西造出来」这个动作本身只有一行，
	 * 真正复杂的是它前面那些准备</b>——而准备之所以存在，全是因为前面章节留下的能力缺口。
	 * @param beanFactory 目标工厂
	 */
	protected void finishBeanFactoryInitialization(ConfigurableListableBeanFactory beanFactory) {
		beanFactory.preInstantiateSingletons();
	}

	/**
	 * 第十二步：收尾。<b>第 15 章填</b>（发布 {@code ContextRefreshedEvent}）， 并在这里调用
	 * {@code LifecycleProcessor#onRefresh()}。
	 */
	protected void finishRefresh() {
		// 第 15 章
	}

	/**
	 * 启动失败时的回滚：销毁所有已创建的单例。
	 * <p>
	 * 注意它<b>不区分</b>「谁造的」——全部销毁。因为一次失败的启动没有任何部分值得保留： 图纸还在，重新 {@code refresh()} 会重新造。
	 */
	protected void destroyBeans() {
		getBeanFactory().destroySingletons();
	}

	/**
	 * 启动失败时把状态退回「未激活」。
	 * <p>
	 * 注意 {@code closed} 也置回 false：失败不是关闭，上下文之后<b>还可以再试一次</b>。
	 * @param ex 触发回滚的异常
	 */
	protected void cancelRefresh(Throwable ex) {
		this.closed.set(false);
		this.active.set(false);
	}

	/**
	 * 第二步的前半段：让子类准备内部工厂。
	 */
	protected abstract void refreshBeanFactory();

	// ---------------------------------------------------------------------
	// 生命周期状态
	// ---------------------------------------------------------------------

	@Override
	public void close() {
		doClose();
	}

	/**
	 * 关闭的实际动作。子类若需要先做自己的清理，覆写它而不是 {@link #close()}。
	 */
	protected void doClose() {
		if (this.active.get()) {
			this.active.set(false);
			destroyBeans();
			this.closed.set(true);
		}
	}

	@Override
	public boolean isActive() {
		return this.active.get();
	}

	@Override
	public String getDisplayName() {
		return this.displayName;
	}

	@Override
	public long getStartupDate() {
		return this.startupDate;
	}

	// ---------------------------------------------------------------------
	// BeanFactory 能力：全部透传
	// ---------------------------------------------------------------------

	/**
	 * 所有取 Bean 的方法都先过这一关。
	 * <p>
	 * 三条消息分别对应三种状态，措辞与真实 Spring（{@code :1277-1288}）一致：
	 * <ul>
	 * <li>{@code ... has not been refreshed yet}</li>
	 * <li>{@code ... has been closed already}</li>
	 * </ul>
	 * 为什么要区分？因为「还没启动」是<b>用法错误</b>（忘了调 {@code refresh()}），
	 * 「已经关闭」是<b>时序错误</b>（在关闭之后还持有引用）。两种错要两种改法。
	 */
	protected void assertBeanFactoryActive() {
		if (!this.active.get()) {
			if (this.closed.get()) {
				throw new IllegalStateException(getDisplayName() + " has been closed already");
			}
			throw new IllegalStateException(getDisplayName() + " has not been refreshed yet");
		}
	}

	@Override
	public Object getBean(String name) {
		assertBeanFactoryActive();
		return getBeanFactory().getBean(name);
	}

	@Override
	public <T> T getBean(String name, Class<T> requiredType) {
		assertBeanFactoryActive();
		return getBeanFactory().getBean(name, requiredType);
	}

	@Override
	public boolean containsBean(String name) {
		assertBeanFactoryActive();
		return getBeanFactory().containsBean(name);
	}

	@Override
	public boolean isSingleton(String name) {
		assertBeanFactoryActive();
		return getBeanFactory().isSingleton(name);
	}

	@Override
	public boolean isPrototype(String name) {
		assertBeanFactoryActive();
		return getBeanFactory().isPrototype(name);
	}

	@Override
	public Class<?> getType(String name) {
		assertBeanFactoryActive();
		return getBeanFactory().getType(name);
	}

	@Override
	public boolean containsBeanDefinition(String beanName) {
		assertBeanFactoryActive();
		return getBeanFactory().containsBeanDefinition(beanName);
	}

	@Override
	public int getBeanDefinitionCount() {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanDefinitionCount();
	}

	@Override
	public String[] getBeanDefinitionNames() {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanDefinitionNames();
	}

	@Override
	public String[] getBeanNamesForType(Class<?> type) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanNamesForType(type);
	}

	@Override
	public <T> Map<String, T> getBeansOfType(Class<T> type) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeansOfType(type);
	}

	@Override
	public StandardEnvironment getEnvironment() {
		return this.environment;
	}

	@Override
	public void addBeanFactoryPostProcessor(BeanFactoryPostProcessor postProcessor) {
		// 注意：这里<b>不能</b>调 assertBeanFactoryActive()——它的用途就是在 refresh() 之前登记。
		// 真实 Spring 同位置也只有一句 Assert.notNull（AbstractApplicationContext:549-552）。
		this.beanFactoryPostProcessors
			.add(Objects.requireNonNull(postProcessor, "BeanFactoryPostProcessor must not be null"));
	}

	@Override
	public BeanFactory getParentBeanFactory() {
		return getBeanFactory().getParentBeanFactory();
	}

	@Override
	public boolean containsLocalBean(String name) {
		assertBeanFactoryActive();
		return getBeanFactory().containsLocalBean(name);
	}

}
