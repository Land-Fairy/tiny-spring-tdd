package org.springframework.beans.factory.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.NoSuchBeanException;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.samples.ch03.NeedsArguments;
import org.springframework.samples.ch03.SlowConstruction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 图纸表的增删查、以及「造不出来的时候报什么」。
 *
 * <p>
 * 这一组测试盯的是<b>边界</b>而不是正常路径：正常路径在 {@link DefaultListableBeanFactoryTests} 里，并发在
 * {@link ConcurrentSingletonCreationTests} 里。
 * 真实项目里出事的几乎都是边界，所以错误消息本身也是被测对象——它是半夜三点的你唯一读得到的东西。
 */
class DefaultListableBeanFactoryCreationTests {

	private DefaultListableBeanFactory factory;

	@BeforeEach
	void setUp() {
		this.factory = new DefaultListableBeanFactory();
	}

	@Test
	@DisplayName("删图纸不等于删对象：已造好的单例还在池子里")
	void removeBeanDefinitionKeepsTheSingleton() {
		this.factory.registerBeanDefinition("bean", definition(BeanDefinition.SCOPE_SINGLETON, Object.class));
		Object bean = this.factory.getBean("bean");

		this.factory.removeBeanDefinition("bean");

		assertThat(this.factory.containsBeanDefinition("bean")).isFalse();
		assertThat(this.factory.containsSingleton("bean")).isTrue();
		assertThat(this.factory.getSingleton("bean")).isSameAs(bean);
	}

	@Test
	@DisplayName("删一张不存在的图纸：报 NoSuchBeanException，消息里带名字")
	void removeUnknownDefinitionThrows() {
		assertThatThrownBy(() -> this.factory.removeBeanDefinition("ghost")).isInstanceOf(NoSuchBeanException.class)
			.hasMessageContaining("ghost");
	}

	@Test
	@DisplayName("取一张不存在的图纸：报 NoSuchBeanException")
	void getUnknownDefinitionThrows() {
		assertThatThrownBy(() -> this.factory.getBeanDefinition("ghost")).isInstanceOf(NoSuchBeanException.class)
			.hasMessageContaining("ghost");
	}

	@Test
	@DisplayName("注册图纸时名字不能为空、图纸不能为 null")
	void invalidRegistrationsAreRejected() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> this.factory.registerBeanDefinition("", new GenericBeanDefinition()))
			.withMessageContaining("Bean name must not be empty");
		assertThatIllegalArgumentException().isThrownBy(() -> this.factory.registerBeanDefinition("bean", null))
			.withMessageContaining("BeanDefinition must not be null");
	}

	@Test
	@DisplayName("这个名字是否已被占用：图纸和池子都算占用")
	void beanNameInUseCoversDefinitionsAndSingletons() {
		assertThat(this.factory.isBeanNameInUse("bean")).isFalse();

		this.factory.registerBeanDefinition("bean", definition(BeanDefinition.SCOPE_SINGLETON, Object.class));
		assertThat(this.factory.isBeanNameInUse("bean")).isTrue();

		this.factory.registerSingleton("direct", new Object());
		assertThat(this.factory.isBeanNameInUse("direct")).isTrue();
		assertThat(this.factory.getBeanDefinitionCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("类名写错：报 Failed to instantiate，并把原始异常留在 cause 里")
	void unknownClassNameFailsWithCause() {
		GenericBeanDefinition definition = new GenericBeanDefinition();
		definition.setBeanClassName("org.springframework.samples.ch03.DoesNotExist");
		this.factory.registerBeanDefinition("ghost", definition);

		assertThatIllegalStateException().isThrownBy(() -> this.factory.getBean("ghost"))
			.withMessageContaining("Failed to instantiate bean 'ghost'")
			.withCauseInstanceOf(ClassNotFoundException.class);
	}

	@Test
	@DisplayName("第 03 章钉住的缺陷已补上：带参数的构造器现在按参数名取依赖（第 04 章）")
	void constructorArgumentIsResolvedByParameterName() {
		this.factory.registerBeanDefinition("name", definition(BeanDefinition.SCOPE_SINGLETON, String.class));
		this.factory.registerBeanDefinition("needsArgs",
				definition(BeanDefinition.SCOPE_SINGLETON, NeedsArguments.class));

		NeedsArguments bean = (NeedsArguments) this.factory.getBean("needsArgs");

		assertThat(bean.name()).isEmpty();
	}

	@Test
	@DisplayName("参数名对不上任何 Bean：报 UnsatisfiedDependency，点名 Bean 与参数")
	void constructorArgumentThatCannotBeResolvedIsReported() {
		this.factory.registerBeanDefinition("needsArgs",
				definition(BeanDefinition.SCOPE_SINGLETON, NeedsArguments.class));

		assertThatThrownBy(() -> this.factory.getBean("needsArgs")).isInstanceOf(UnsatisfiedDependencyException.class)
			.hasMessageContaining("needsArgs")
			.hasMessageContaining("'name'");
	}

	@Test
	@DisplayName("setBeanClass 与 setBeanClassName 两条入口都能供出类名")
	void beanClassCanBeSuppliedAsClassObject() {
		GenericBeanDefinition definition = new GenericBeanDefinition();

		definition.setBeanClassName("java.lang.String");
		assertThat(definition.getBeanClassName()).isEqualTo("java.lang.String");

		definition.setBeanClass(SlowConstruction.class);
		assertThat(definition.getBeanClassName()).isEqualTo(SlowConstruction.class.getName());
	}

	@Test
	@DisplayName("原型作用域不进池子：每次 getBean 都重新造")
	void prototypeIsNeverPooled() {
		SlowConstruction.resetNonBlocking();
		this.factory.registerBeanDefinition("prototype",
				definition(BeanDefinition.SCOPE_PROTOTYPE, SlowConstruction.class));

		assertThat(this.factory.getBean("prototype")).isNotSameAs(this.factory.getBean("prototype"));
		assertThat(SlowConstruction.creations()).isEqualTo(2);
		assertThat(this.factory.containsSingleton("prototype")).isFalse();
	}

	private GenericBeanDefinition definition(String scope, Class<?> beanClass) {
		GenericBeanDefinition definition = new GenericBeanDefinition();
		definition.setBeanClass(beanClass);
		definition.setScope(scope);
		return definition;
	}

}
