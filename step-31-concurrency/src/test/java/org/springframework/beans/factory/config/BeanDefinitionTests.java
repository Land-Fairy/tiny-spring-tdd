package org.springframework.beans.factory.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.samples.ch01.EnglishGreeter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 02 章：BeanDefinition 作为「图纸」的行为规格。
 *
 * <p>
 * 这一组测试只关心一件事——<b>图纸能表达什么</b>。它不造任何对象，也不碰容器。把「图纸」和「按图纸造」 分成两组测试，是本章最重要的一条分界线。
 */
class BeanDefinitionTests {

	@Test
	@DisplayName("刚 new 出来的图纸：作用域是空串，但已经被算作单例")
	void defaultScopeIsBlankButTreatedAsSingleton() {
		BeanDefinition definition = new GenericBeanDefinition();

		// 真实 Spring 的默认作用是 ""（SCOPE_DEFAULT），不是 "singleton"。
		assertThat(definition.getScope()).isEmpty();
		// 空串被当成单例——这是「没声明」与「声明为单例」在语义上等价的证据。
		assertThat(definition.isSingleton()).isTrue();
		assertThat(definition.isPrototype()).isFalse();
	}

	@Test
	@DisplayName("作用域写成 prototype：不再是单例")
	void prototypeScopeIsNotSingleton() {
		BeanDefinition definition = new GenericBeanDefinition();
		definition.setScope(BeanDefinition.SCOPE_PROTOTYPE);

		assertThat(definition.getScope()).isEqualTo("prototype");
		assertThat(definition.isPrototype()).isTrue();
		assertThat(definition.isSingleton()).isFalse();
	}

	@Test
	@DisplayName("显式声明 singleton：与空串等价")
	void explicitSingletonScope() {
		BeanDefinition definition = new GenericBeanDefinition();
		definition.setScope(BeanDefinition.SCOPE_SINGLETON);

		assertThat(definition.isSingleton()).isTrue();
		assertThat(definition.isPrototype()).isFalse();
	}

	@Test
	@DisplayName("作用域被设成 null：既不是单例也不是原型——一个真实的坑")
	void nullScopeMakesItNeitherSingletonNorPrototype() {
		BeanDefinition definition = new GenericBeanDefinition();
		definition.setScope(null);

		// 真实 Spring 里 isSingleton() 是 SCOPE_SINGLETON.equals(scope) ||
		// SCOPE_DEFAULT.equals(scope)，
		// null 两个 equals 都不成立。所以「清空作用域」和「没有作用域」不是一回事。
		assertThat(definition.isSingleton()).isFalse();
		assertThat(definition.isPrototype()).isFalse();
	}

	@Test
	@DisplayName("类名或 Class，只是同一个字段的两种形态")
	void beanClassNameAndBeanClassShareOneField() {
		BeanDefinition byName = new GenericBeanDefinition();
		byName.setBeanClassName("org.springframework.samples.ch01.EnglishGreeter");
		assertThat(byName.getBeanClassName()).isEqualTo("org.springframework.samples.ch01.EnglishGreeter");

		GenericBeanDefinition byClass = new GenericBeanDefinition();
		byClass.setBeanClass(EnglishGreeter.class);
		// 塞进去的是 Class，取出来的是名字——同一个字段，两种形态。
		assertThat(byClass.getBeanClassName()).isEqualTo(EnglishGreeter.class.getName());
	}

	@Test
	@DisplayName("懒加载默认关，能被打开")
	void lazyInitDefaultsToFalse() {
		BeanDefinition definition = new GenericBeanDefinition();
		assertThat(definition.isLazyInit()).isFalse();

		definition.setLazyInit(true);
		assertThat(definition.isLazyInit()).isTrue();
	}

	@Test
	@DisplayName("初始化与销毁方法名只是两个字符串，容器还没打算调用它们")
	void initAndDestroyMethodNamesAreJustStrings() {
		BeanDefinition definition = new GenericBeanDefinition();
		assertThat(definition.getInitMethodName()).isNull();
		assertThat(definition.getDestroyMethodName()).isNull();

		definition.setInitMethodName("init");
		definition.setDestroyMethodName("close");

		assertThat(definition.getInitMethodName()).isEqualTo("init");
		assertThat(definition.getDestroyMethodName()).isEqualTo("close");
	}

	@Test
	@DisplayName("角色默认是应用 Bean，容器内部件用 INFRASTRUCTURE 标出来")
	void roleDefaultsToApplication() {
		BeanDefinition definition = new GenericBeanDefinition();
		assertThat(definition.getRole()).isEqualTo(BeanDefinition.ROLE_APPLICATION);
		assertThat(BeanDefinition.ROLE_APPLICATION).isZero();
		assertThat(BeanDefinition.ROLE_SUPPORT).isEqualTo(1);
		assertThat(BeanDefinition.ROLE_INFRASTRUCTURE).isEqualTo(2);

		definition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
		assertThat(definition.getRole()).isEqualTo(BeanDefinition.ROLE_INFRASTRUCTURE);
	}

	@Test
	@DisplayName("描述与抽象标记是可选信息")
	void descriptionAndAbstractFlag() {
		BeanDefinition definition = new GenericBeanDefinition();
		assertThat(definition.getDescription()).isNull();
		assertThat(definition.isAbstract()).isFalse();

		definition.setDescription("英文问候语");
		assertThat(definition.getDescription()).isEqualTo("英文问候语");
	}

	@Test
	@DisplayName("图纸的出处默认没有——BeanMetadataElement 的默认实现")
	void sourceDefaultsToNull() {
		BeanDefinition definition = new GenericBeanDefinition();
		assertThat(definition.getSource()).isNull();
	}

}
