package org.springframework.samples.ch22;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.GenericApplicationContext;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 两个<b>同简单类名</b>、不同包的类被同时 {@code @Import}：默认 Bean 名会撞车。
 *
 * <p>
 * 这是「默认 Bean 名」这条规则自带的坑——{@code same.a.Foo} 与 {@code same.b.Foo} 算出来都叫 {@code foo}。 真实
 * Spring 的做法是<b>当场报错</b>（{@code ConflictingBeanDefinitionException}），而不是留一个、丢一个：
 * 静默丢弃会让「明明导入了却没生效」变成一场考古。
 */
@DisplayName("@Import 的名字冲突")
class CollisionTests {

	@Configuration
	@Import({ org.springframework.samples.ch22.same.a.Foo.class, org.springframework.samples.ch22.same.b.Foo.class })
	public static class CollidingConfiguration {

	}

	@Test
	@DisplayName("同名类算默认 Bean 名时撞车，必须报错而不是静默丢掉一个")
	void complainsAboutConflictingClassNames() {
		assertThatThrownBy(() -> {
			try (GenericApplicationContext context = Ch22Contexts.standard(CollidingConfiguration.class)) {
				context.getBean("foo");
			}
		}).isInstanceOf(BeanDefinitionStoreException.class)
			.hasMessageContaining("conflicts with existing, non-compatible bean definition");
	}

}
