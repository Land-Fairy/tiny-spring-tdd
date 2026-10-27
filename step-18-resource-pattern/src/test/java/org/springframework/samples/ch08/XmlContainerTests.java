package org.springframework.samples.ch08;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * 端到端：从一个 XML 文件到「拿到装配完成的对象」。
 *
 * <p>
 * 这一条测试把前七章全部串起来了——图纸（02）、注册表（03）、构造器注入（04）、 属性填充与初始化回调（05）、接口分层（06）、资源抽象（07）、XML 解析（08）。
 */
class XmlContainerTests {

	@Test
	@DisplayName("从 classpath 上的 XML 拿到装配完成的对象")
	void endToEndFromXmlToWiredObject() {
		DefaultListableBeanFactory factory = new DefaultListableBeanFactory();

		new XmlBeanDefinitionReader(factory).loadBeanDefinitions(new ClassPathResource("ch08/beans.xml"));

		GreetingController controller = factory.getBean("controller", GreetingController.class);

		// prefix 来自 value="Mr. "，greeter 来自 ref="greeter"。
		// 少任何一个断言都会变：缺 prefix 得到 "Hello, Ann"，缺 greeter 得到 NPE。
		assertThat(controller.greet("Ann")).isEqualTo("Hello, Mr. Ann");
	}

	@Test
	@DisplayName("懒加载的原型图纸不会被提前创建")
	void lazyPrototypeIsNotInstantiatedEagerly() {
		DefaultListableBeanFactory factory = new DefaultListableBeanFactory();

		new XmlBeanDefinitionReader(factory).loadBeanDefinitions(new ClassPathResource("ch08/beans.xml"));
		factory.preInstantiateSingletons();

		assertThat(factory.containsSingleton("lazyGreeter")).isFalse();
		assertThat(factory.containsSingleton("controller")).isTrue();
	}

	@Test
	@DisplayName("引用不存在的 bean，错误发生在创建阶段且说清了引用关系")
	void brokenReferenceFailsInADiagnosableWay() {
		DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
		String xml = """
				<beans>
					<bean id="controller" class="org.springframework.samples.ch08.GreetingController">
						<property name="greeter" ref="missingGreeter"/>
					</bean>
				</beans>
				""";

		new XmlBeanDefinitionReader(factory)
			.loadBeanDefinitions(new ByteArrayResource(xml.getBytes(StandardCharsets.UTF_8)));

		assertThatExceptionOfType(BeanCreationException.class).isThrownBy(() -> factory.getBean("controller"))
			.withMessageContaining("missingGreeter");
	}

	@Test
	@DisplayName("同一个 XML 换一个 class 属性就换掉实现——第 01 章那个愿望终于真的成立了")
	void theSameXmlWithAnotherClassChangesTheImplementation() {
		DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
		String xml = """
				<beans>
					<bean id="greeter" class="org.springframework.samples.ch08.ChineseGreeter"/>
				</beans>
				""";

		new XmlBeanDefinitionReader(factory)
			.loadBeanDefinitions(new ByteArrayResource(xml.getBytes(StandardCharsets.UTF_8)));

		Greeter greeter = factory.getBean("greeter", Greeter.class);

		assertThat(greeter.greet("Ann")).isEqualTo("你好，Ann");
	}

}
