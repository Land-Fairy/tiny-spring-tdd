package org.springframework.context.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.samples.ch09.CountingBean;
import org.springframework.samples.ch09.ExplodingBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * {@code refresh()} 的对外契约：什么时候能用、什么时候不能用、失败之后留下什么。
 *
 * <p>
 * 这一组测试刻意只碰<b>公开 API</b>（{@code refresh} / {@code getBean} / {@code close} /
 * {@code isActive}），不碰内部字段。因为第 09 章要立的规矩是「上下文有生命周期」， 而生命周期是外部可见的行为，不是内部实现细节。
 */
class RefreshLifecycleTests {

	private GenericApplicationContext context;

	@BeforeEach
	void setUp() {
		CountingBean.reset();
		this.context = new GenericApplicationContext("test-context");
	}

	@AfterEach
	void tearDown() {
		if (this.context.isActive()) {
			this.context.close();
		}
	}

	@Test
	@DisplayName("refresh 之前取 Bean：报「还没 refresh」，而不是 NPE 或「没有这个 Bean」")
	void getBeanBeforeRefreshIsRejected() {
		register("counter", CountingBean.class);

		assertThatIllegalStateException().isThrownBy(() -> this.context.getBean("counter"))
			.withMessageContaining("test-context")
			.withMessageContaining("has not been refreshed yet");
	}

	@Test
	@DisplayName("close 之后取 Bean：报「已经关了」，与「还没 refresh」区分开")
	void getBeanAfterCloseIsRejected() {
		register("counter", CountingBean.class);
		this.context.refresh();
		this.context.close();

		assertThatIllegalStateException().isThrownBy(() -> this.context.getBean("counter"))
			.withMessageContaining("has been closed already");
	}

	@Test
	@DisplayName("refresh 会把非懒加载单例提前造好：不调用 getBean 也已经造出来了")
	void refreshInstantiatesEagerSingletons() {
		register("counter", CountingBean.class);

		assertThat(CountingBean.creations()).isZero();

		this.context.refresh();

		assertThat(CountingBean.creations()).isEqualTo(1);
		assertThat(this.context.getBeanFactory().containsSingleton("counter")).isTrue();
	}

	@Test
	@DisplayName("refresh 不会碰懒加载的 Bean")
	void refreshSkipsLazyBeans() {
		GenericBeanDefinition definition = register("lazy", CountingBean.class);
		definition.setLazyInit(true);

		this.context.refresh();

		assertThat(CountingBean.creations()).isZero();
		assertThat(this.context.getBean("lazy", CountingBean.class).serial()).isEqualTo(1);
		assertThat(CountingBean.creations()).isEqualTo(1);
	}

	@Test
	@DisplayName("refresh 不会碰原型作用域的 Bean")
	void refreshSkipsPrototypes() {
		GenericBeanDefinition definition = register("prototype", CountingBean.class);
		definition.setScope(BeanDefinition.SCOPE_PROTOTYPE);

		this.context.refresh();

		assertThat(CountingBean.creations()).isZero();
		assertThat(this.context.containsBean("prototype")).isTrue();
	}

	@Test
	@DisplayName("refresh 之后 isActive 为真，close 之后为假")
	void activeFlagFollowsTheLifecycle() {
		assertThat(this.context.isActive()).isFalse();

		this.context.refresh();
		assertThat(this.context.isActive()).isTrue();

		this.context.close();
		assertThat(this.context.isActive()).isFalse();
	}

	@Test
	@DisplayName("refresh 期间抛异常：已经造好的单例全部销毁，上下文回到未激活状态")
	void failedRefreshDestroysAlreadyCreatedSingletons() {
		register("counter", CountingBean.class);
		register("exploding", ExplodingBean.class);

		assertThatIllegalStateException().isThrownBy(() -> this.context.refresh())
			.withMessageContaining("Failed to instantiate");

		assertThat(CountingBean.creations()).isEqualTo(1);
		assertThat(this.context.getBeanFactory().containsSingleton("counter")).isFalse();
		assertThat(this.context.isActive()).isFalse();
		assertThatIllegalStateException().isThrownBy(() -> this.context.getBean("counter"))
			.withMessageContaining("has not been refreshed yet");
	}

	@Test
	@DisplayName("失败之后换一个上下文还能正常启动：图纸没有被污染")
	void aFailedRefreshDoesNotPolluteTheDefinitions() {
		register("counter", CountingBean.class);
		register("exploding", ExplodingBean.class);
		assertThatIllegalStateException().isThrownBy(() -> this.context.refresh());

		GenericApplicationContext retry = new GenericApplicationContext("retry-context");
		retry.getBeanFactory().registerBeanDefinition("counter", definition(CountingBean.class));
		retry.refresh();

		assertThat(retry.getBean("counter", CountingBean.class).serial()).isEqualTo(2);
		retry.close();
	}

	@Test
	@DisplayName("GenericApplicationContext 拒绝第二次 refresh")
	void secondRefreshIsRejected() {
		this.context.refresh();

		assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> this.context.refresh())
			.withMessageContaining("does not support multiple refresh attempts");
	}

	@Test
	@DisplayName("getStartupDate 在 refresh 之后才有值")
	void startupDateIsRecorded() {
		assertThat(this.context.getStartupDate()).isZero();

		this.context.refresh();

		assertThat(this.context.getStartupDate()).isPositive();
	}

	@Test
	@DisplayName("上下文把 Listable 与 Hierarchical 的能力直接透传给内部的 BeanFactory")
	void listableAndHierarchicalCapabilitiesAreDelegated() {
		register("counter", CountingBean.class);
		register("counter2", CountingBean.class);

		this.context.refresh();

		assertThat(this.context.getBeanDefinitionCount()).isEqualTo(2);
		assertThat(this.context.getBeanDefinitionNames()).containsExactly("counter", "counter2");
		assertThat(this.context.getBeanNamesForType(CountingBean.class)).containsExactly("counter", "counter2");
		assertThat(this.context.getBeansOfType(CountingBean.class)).containsOnlyKeys("counter", "counter2");
		assertThat(this.context.containsBeanDefinition("counter")).isTrue();
		assertThat(this.context.containsLocalBean("counter")).isTrue();
		assertThat(this.context.getParentBeanFactory()).isNull();
	}

	@Test
	@DisplayName("close 之后内部的 BeanFactory 单例池已清空")
	void closeClearsTheSingletonPool() {
		register("counter", CountingBean.class);
		this.context.refresh();

		assertThat(this.context.getBeanFactory().getSingletonNames()).containsExactly("counter");

		this.context.close();

		assertThat(this.context.getBeanFactory().getSingletonCount()).isZero();
	}

	private GenericBeanDefinition register(String beanName, Class<?> beanClass) {
		GenericBeanDefinition definition = definition(beanClass);
		this.context.getBeanFactory().registerBeanDefinition(beanName, definition);
		return definition;
	}

	private GenericBeanDefinition definition(Class<?> beanClass) {
		GenericBeanDefinition definition = new GenericBeanDefinition();
		definition.setBeanClassName(beanClass.getName());
		return definition;
	}

}
