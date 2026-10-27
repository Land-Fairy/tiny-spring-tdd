package org.springframework.samples.ch15;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.ClassPathResource;

/**
 * 测试用的上下文工厂：造一个「行内读 XML」的 {@link GenericApplicationContext}。
 *
 * <p>
 * 为什么不直接用 {@code ClassPathXmlApplicationContext}？因为它在构造器里就 {@code refresh()} 了， 而本章的测试需要在
 * {@code refresh()} <b>过程中</b>偷看（比如在第十步结束时检查监听器有没有被创建）。
 */
public final class Ch15Contexts {

	private Ch15Contexts() {
	}

	/**
	 * 造一个从 classpath 读 XML 的上下文（不自动 refresh）。
	 * @param location classpath 资源位置
	 * @return 未启动的上下文
	 */
	public static XmlContext xmlContext(String location) {
		return new XmlContext(location);
	}

	/**
	 * 行内读 XML 的上下文。
	 */
	public static class XmlContext extends GenericApplicationContext {

		private final String location;

		/**
		 * 用资源位置造上下文。
		 * @param location classpath 资源位置
		 */
		public XmlContext(String location) {
			super("Ch15 XmlContext for [" + location + "]");
			this.location = location;
		}

		@Override
		protected void refreshBeanFactory() {
			super.refreshBeanFactory();
			DefaultListableBeanFactory beanFactory = getBeanFactory();
			new XmlBeanDefinitionReader(beanFactory).loadBeanDefinitions(new ClassPathResource(this.location));
		}

	}

}
