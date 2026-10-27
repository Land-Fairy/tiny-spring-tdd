package org.springframework.samples.ch22;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.context.support.GenericApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @Lazy}：只是图纸上的一个布尔位，容器在建单例的那个循环里跳过它。
 */
@DisplayName("@Lazy")
class LazyTests {

	@BeforeEach
	void resetCounter() {
		CreationTracker.reset();
	}

	@Test
	@DisplayName("刷新之后：急的已经造好，懒的连影子都没有")
	void doesNotCreateTheLazyBeanDuringRefresh() {
		try (GenericApplicationContext context = Ch22Contexts.standard(LazyConfiguration.class)) {
			assertThat(CreationTracker.createdCount()).isEqualTo(1);
			assertThat(context.getBean("eagerBean", TrackedBean.class).getName()).isEqualTo("eager");
		}
	}

	@Test
	@DisplayName("第一次取的时候才造，而且只造一次")
	void createsTheLazyBeanOnFirstAccess() {
		try (GenericApplicationContext context = Ch22Contexts.standard(LazyConfiguration.class)) {
			TrackedBean first = context.getBean("lazyBean", TrackedBean.class);
			TrackedBean second = context.getBean("lazyBean", TrackedBean.class);

			assertThat(CreationTracker.createdCount()).isEqualTo(2);
			assertThat(first).isSameAs(second);
		}
	}

	@Test
	@DisplayName("按类型列名字不会把它造出来（图纸表里有，对象表里没有）")
	void listingNamesDoesNotCreateIt() {
		try (GenericApplicationContext context = Ch22Contexts.standard(LazyConfiguration.class)) {
			assertThat(context.getBeanNamesForType(TrackedBean.class)).containsExactlyInAnyOrder("eagerBean",
					"lazyBean");
			assertThat(CreationTracker.createdCount()).isEqualTo(1);
		}
	}

}
