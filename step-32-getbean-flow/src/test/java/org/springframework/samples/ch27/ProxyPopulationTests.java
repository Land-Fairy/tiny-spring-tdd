package org.springframework.samples.ch27;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.aop.framework.AdvisedSupport;
import org.springframework.aop.framework.JdkDynamicAopProxy;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.NotWritablePropertyException;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * 第 27 章：属性填充发生在代理创建之前 —— 那么「代理对象上的字段」是谁填的？
 * <p>
 * 答案：<b>没有人填，代理上根本没有字段</b>。字段永远在目标里，容器填的是目标。
 *
 * @see org.springframework.aop.framework.TargetSource
 */
class ProxyPopulationTests {

	@Test
	@DisplayName("属性填充打在代理上：接口里没有的属性直接失败，接口里有的属性写到了目标里")
	void populationOnAProxyHitsTheTargetNotTheProxy() {
		LibraryImpl target = new LibraryImpl();
		Library proxy = (Library) new JdkDynamicAopProxy(new AdvisedSupport(target)).getProxy();

		// floor 不在接口里 —— 代理没有这个 setter，属性填充无路可走
		assertThatExceptionOfType(NotWritablePropertyException.class)
			.isThrownBy(() -> new BeanWrapperImpl(proxy).setPropertyValue(new PropertyValue("floor", 3)))
			.withMessageContaining("floor");

		// title 在接口里 —— 写成功了，但写进的是目标对象的字段
		new BeanWrapperImpl(proxy).setPropertyValue(new PropertyValue("title", "被代理馆"));
		assertThat(target.getTitle()).isEqualTo("被代理馆");
	}

	@Test
	@DisplayName("字段注入打在代理上：不报错，但字段永远是 null")
	void fieldInjectionOnAProxySilentlyDisappears() {
		ServiceUser target = new ServiceUser();
		Describable proxy = (Describable) new JdkDynamicAopProxy(new AdvisedSupport(target)).getProxy();
		AutowiredAnnotationBeanPostProcessor processor = new AutowiredAnnotationBeanPostProcessor(
				beanFactoryWithLibrary());

		// 不抛异常 —— 所以没人会发现出了问题
		processor.postProcessProperties(new MutablePropertyValues(), proxy, "serviceUser");

		// 代理类上没有 library 这个字段，注入无处可写
		assertThat(target.getLibrary()).isNull();
		assertThatExceptionOfType(NullPointerException.class).isThrownBy(proxy::describe);
	}

	@Test
	@DisplayName("容器在代理之前填好目标，代理读到的值来自目标")
	void theProxyOnlyDelegatesToTheFilledTarget() {
		LibraryImpl target = new LibraryImpl();
		// 这一步就是容器的 populateBean：它作用在目标上，发生在代理创建之前
		new BeanWrapperImpl(target).setPropertyValue(new PropertyValue("title", "被容器填好的馆"));

		Library proxy = (Library) new JdkDynamicAopProxy(new AdvisedSupport(target)).getProxy();

		assertThat(proxy.getTitle()).isEqualTo("被容器填好的馆");
		assertThat(proxy).isNotInstanceOf(LibraryImpl.class);
	}

	private DefaultListableBeanFactory beanFactoryWithLibrary() {
		DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
		LibraryImpl library = new LibraryImpl();
		library.setTitle("城市图书馆");
		factory.registerSingleton("library", library);
		return factory;
	}

}
