package org.springframework.beans.factory;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.samples.ch06.ChineseGreeter;
import org.springframework.samples.ch06.EnglishGreeter;
import org.springframework.samples.ch06.Greeter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ListableBeanFactory} 多出来的那一点承诺：**能枚举**。
 *
 * <p>
 * 为什么「枚举」值得单独一层？因为它是唯一<b>不需要你事先知道名字</b>的能力。{@code BeanFactory} 的所有方法 都以名字或类型为入口，而
 * {@code getBeanNamesForType} 的答案是「容器里的全部匹配项」——这个能力 在实现上要遍历所有图纸，在语义上要决定「没实例化的 Bean
 * 算不算」。两件事都不是 {@code getBean} 的延伸。
 */
class ListableBeanFactoryTests {

	private final ListableBeanFactory beanFactory = newFactory();

	private static ListableBeanFactory newFactory() {
		DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
		factory.registerBeanDefinition("english", definition(EnglishGreeter.class));
		factory.registerBeanDefinition("chinese", definition(ChineseGreeter.class));
		factory.registerBeanDefinition("plain", definition(StringBuilder.class));
		return factory;
	}

	private static GenericBeanDefinition definition(Class<?> type) {
		GenericBeanDefinition definition = new GenericBeanDefinition();
		definition.setBeanClassName(type.getName());
		return definition;
	}

	@Test
	@DisplayName("getBeanDefinitionCount / containsBeanDefinition：图纸表的基本问题")
	void countsAndContainsDefinitions() {
		assertThat(this.beanFactory.getBeanDefinitionCount()).isEqualTo(3);
		assertThat(this.beanFactory.containsBeanDefinition("english")).isTrue();
		assertThat(this.beanFactory.containsBeanDefinition("absent")).isFalse();
	}

	@Test
	@DisplayName("getBeanDefinitionNames：不造对象也能拿到全部名字")
	void namesAreAvailableWithoutInstantiation() {
		assertThat(this.beanFactory.getBeanDefinitionNames()).containsExactlyInAnyOrder("english", "chinese", "plain");
	}

	@Test
	@DisplayName("getBeanNamesForType：只返回类型匹配的那些名字")
	void namesForTypeFiltersByType() {
		assertThat(this.beanFactory.getBeanNamesForType(Greeter.class)).containsExactlyInAnyOrder("english", "chinese");
	}

	@Test
	@DisplayName("getBeansOfType：一次拿到名字 → 实例，且真正造出了对象")
	void beansOfTypeReturnsInstances() {
		Map<String, Greeter> greeters = this.beanFactory.getBeansOfType(Greeter.class);

		assertThat(greeters).containsOnlyKeys("english", "chinese");
		assertThat(greeters.get("english").greet("Ann")).isEqualTo("Hello, Ann");
	}

	@Test
	@DisplayName("查一个没有任何实现的类型：返回空结果，不抛异常")
	void unknownTypeYieldsEmptyResults() {
		assertThat(this.beanFactory.getBeanNamesForType(Runnable.class)).isEmpty();
		assertThat(this.beanFactory.getBeansOfType(Runnable.class)).isEmpty();
	}

}
