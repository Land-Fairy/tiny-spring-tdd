package org.springframework.context;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;

/**
 * 可配置、可关闭的上下文——也就是「有生命周期」的那个接口。
 *
 * <p>
 * 三个方法定义了一条时间线：{@code refresh()} 之前不能取 Bean，{@code refresh()} 之后可以， {@code close()}
 * 之后又不能。<b>这条时间线是本接口存在的全部理由</b>： 没有它，容器在任何时刻都能用，也就没有任何时刻能保证「所有单例都已就绪」。
 *
 * <p>
 * 这里 extends {@link AutoCloseable} 而不是 {@code java.io.Closeable}，是个刻意的选择：
 * {@code Closeable#close()} 声明了 {@code throws IOException}，而关闭容器不会抛 IO 异常。 用
 * {@code AutoCloseable} 可以把那个受检异常去掉，于是 {@code try (context) {...}} 里不需要 写一个毫无意义的
 * {@code catch (IOException)}。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.context.ConfigurableApplicationContext}
 * （288 行），它 extends {@code ApplicationContext, Lifecycle, Closeable}，并另有
 * {@code setParent} / {@code addBeanFactoryPostProcessor} /
 * {@code addApplicationListener} / {@code getBeanFactory} / {@code getEnvironment} /
 * {@code setEnvironment} / {@code addProtocolResolver} 等十来个方法。本书保留其中六个 （本章新增
 * {@code getEnvironment} 与 {@code addBeanFactoryPostProcessor}）。
 *
 * @see AbstractApplicationContext
 * @see org.springframework.context.ConfigurableApplicationContext
 */
public interface ConfigurableApplicationContext extends ApplicationContext, AutoCloseable {

	/**
	 * 启动这个上下文。
	 * <p>
	 * 它是<b>幂等失败</b>的：重复调用会抛 {@link IllegalStateException}，而不是什么也不做。
	 * @throws IllegalStateException 已经启动过
	 */
	void refresh() throws IllegalStateException;

	/**
	 * 关闭这个上下文，销毁所有单例。
	 */
	@Override
	void close();

	/**
	 * 注册一个 JVM 关闭钩子：进程退出时自动调用 {@link #close()}。
	 *
	 * <p>
	 * 这是「Bean 的销毁回调到底什么时候跑」的最终答案——不注册它，只有显式 {@code close()} 才会销毁；而真实应用往往是被 Ctrl+C
	 * 或容器编排系统杀掉的，没人会替你调 {@code close()}。
	 * @see #close()
	 */
	void registerShutdownHook();

	/**
	 * 当前是否处于「已启动且未关闭」状态。
	 * @return 是否可用
	 */
	boolean isActive();

	/**
	 * 取内部那个 BeanFactory。
	 * <p>
	 * 它存在的原因是：上下文<b>不自带</b>注册图纸的能力。想注册图纸必须拿到工厂 （或者用
	 * {@code ClassPathXmlApplicationContext} 让它从 XML 里读）。
	 * @return 内部的 BeanFactory
	 */
	ConfigurableListableBeanFactory getBeanFactory();

	/**
	 * 环境：容器访问外部配置值的唯一入口。
	 * @return 环境对象
	 */
	Environment getEnvironment();

	/**
	 * 手工登记一个<b>图纸后处理器</b>。
	 * <p>
	 * 两个用途：其一，它比「把后处理器写成 Bean」更早期——程序式登记的会在 {@code refresh()} 的第 5 步被最先调用；其二，本书的 XML
	 * 读取器还不支持构造器参数注入（第 04 章只做了容器自己用的构造器解析）， 所以像
	 * {@code PropertySourcesPlaceholderConfigurer} 这种需要传入 {@code Environment}
	 * 的后处理器，只能用这个方法登记。
	 * @param postProcessor 后处理器
	 */
	void addBeanFactoryPostProcessor(BeanFactoryPostProcessor postProcessor);

}
