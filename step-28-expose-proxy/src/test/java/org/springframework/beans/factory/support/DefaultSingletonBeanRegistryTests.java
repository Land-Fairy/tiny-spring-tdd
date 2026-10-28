package org.springframework.beans.factory.support;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * {@link DefaultSingletonBeanRegistry} 的接口行为测试：只看池子本身，不牵扯图纸。
 *
 * <p>
 * 单独测它的理由：这是一个可以独立成立的小模块（真实 Spring 里它也是独立的一层，位于继承链最底端）。 把它和
 * {@code DefaultListableBeanFactory} 混在一起测，一旦红了就分不清是「池子错了」还是「图纸错了」。
 */
class DefaultSingletonBeanRegistryTests {

	private DefaultSingletonBeanRegistry registry;

	@BeforeEach
	void setUp() {
		this.registry = new DefaultSingletonBeanRegistry();
	}

	@Test
	@DisplayName("刚建好的池子是空的")
	void newRegistryIsEmpty() {
		assertThat(this.registry.getSingleton("anything")).isNull();
		assertThat(this.registry.containsSingleton("anything")).isFalse();
		assertThat(this.registry.getSingletonCount()).isZero();
		assertThat(this.registry.getSingletonNames()).isEmpty();
	}

	@Test
	@DisplayName("registerSingleton 之后按名字取回的就是同一个对象")
	void registeredSingletonIsReturnedAsSameInstance() {
		Object bean = new Object();

		this.registry.registerSingleton("bean", bean);

		assertThat(this.registry.getSingleton("bean")).isSameAs(bean);
		assertThat(this.registry.containsSingleton("bean")).isTrue();
		assertThat(this.registry.getSingletonCount()).isEqualTo(1);
		assertThat(this.registry.getSingletonNames()).containsExactly("bean");
	}

	@Test
	@DisplayName("名字或对象为 null 直接拒绝：池子不接受无名的东西")
	void nullNameOrNullObjectIsRejected() {
		assertThatIllegalArgumentException().isThrownBy(() -> this.registry.registerSingleton(null, new Object()))
			.withMessageContaining("Bean name must not be null");
		assertThatIllegalArgumentException().isThrownBy(() -> this.registry.registerSingleton("bean", null))
			.withMessageContaining("Singleton object must not be null");
	}

	@Test
	@DisplayName("同名注册两次要报错，而不是静默覆盖——否则前一个对象就是凭空消失")
	void registeringSameNameTwiceFails() {
		Object first = new Object();
		this.registry.registerSingleton("bean", first);

		assertThatIllegalStateException().isThrownBy(() -> this.registry.registerSingleton("bean", new Object()))
			.withMessageContaining("there is already object");
		assertThat(this.registry.getSingleton("bean")).isSameAs(first);
	}

	@Test
	@DisplayName("getSingletonNames 按注册顺序返回，不受哈希顺序影响")
	void singletonNamesKeepRegistrationOrder() {
		this.registry.registerSingleton("zulu", new Object());
		this.registry.registerSingleton("alpha", new Object());
		this.registry.registerSingleton("mike", new Object());

		assertThat(this.registry.getSingletonNames()).containsExactly("zulu", "alpha", "mike");
	}

	@Test
	@DisplayName("工厂只在池子空的时候跑，第二次 getSingleton 直接命中池子")
	void factoryRunsOnlyOnce() {
		AtomicInteger invocations = new AtomicInteger();
		Object bean = new Object();

		Object first = this.registry.getSingleton("bean", () -> {
			invocations.incrementAndGet();
			return bean;
		});
		Object second = this.registry.getSingleton("bean", () -> {
			invocations.incrementAndGet();
			return new Object();
		});

		assertThat(first).isSameAs(bean);
		assertThat(second).isSameAs(bean);
		assertThat(invocations).hasValue(1);
	}

	@Test
	@DisplayName("getSingleton(名字, 工厂) 的两个参数都不能是 null")
	void nullArgumentsToGetSingletonAreRejected() {
		assertThatIllegalArgumentException().isThrownBy(() -> this.registry.getSingleton(null, Object::new))
			.withMessageContaining("Bean name must not be null");
		assertThatIllegalArgumentException().isThrownBy(() -> this.registry.getSingleton("bean", null))
			.withMessageContaining("Singleton factory must not be null");
	}

	@Test
	@DisplayName("removeSingleton 把对象摘掉，名字也从名单里消失")
	void removeSingletonRemovesObjectAndName() {
		Object bean = new Object();
		this.registry.registerSingleton("bean", bean);

		assertThat(this.registry.removeSingleton("bean")).isSameAs(bean);

		assertThat(this.registry.containsSingleton("bean")).isFalse();
		assertThat(this.registry.getSingletonCount()).isZero();
		assertThat(this.registry.getSingletonNames()).isEmpty();
	}

	@Test
	@DisplayName("摘一个本来就没有的名字：返回 null，不抛异常")
	void removeMissingSingletonReturnsNull() {
		assertThat(this.registry.removeSingleton("ghost")).isNull();
	}

}
