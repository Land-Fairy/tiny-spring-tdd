package org.springframework.beans.factory.naive;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanException;
import org.springframework.samples.ch01.ChineseGreeter;
import org.springframework.samples.ch01.EnglishGreeter;
import org.springframework.samples.ch01.Greeter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * 第 01 章「失败场景 2：需要按类型取」的现场。
 *
 * <p>
 * 最后两个测试是全章最狠的一对：同一份注册内容、同样两个实现， <b>只把 Bean 的名字换掉，容器选中的实现就变了</b>。
 */
class GetByTypeTests {

	private final NaiveContainer container = new NaiveContainer();

	@Test
	@DisplayName("只有唯一实现时，按类型取得到它")
	void singleCandidateIsFound() {
		this.container.register("greeter", new EnglishGreeter());
		assertThat(this.container.getBean(Greeter.class)).isInstanceOf(EnglishGreeter.class);
	}

	@Test
	@DisplayName("没有任何实现时，按类型取会抛 NoSuchBeanException")
	void noCandidateThrows() {
		this.container.register("greeter", new EnglishGreeter());
		assertThatExceptionOfType(NoSuchBeanException.class).isThrownBy(() -> this.container.getBean(Runnable.class))
			.withMessageContaining("No qualifying bean of type");
	}

	@Test
	@DisplayName("缺陷 2：两个实现时，胜出的是 HashMap 迭代顺序里的第一个，不是你想要的那个")
	void byTypeReturnsFirstMatchInHashMapOrder() {
		this.container.register("englishGreeter", new EnglishGreeter());
		this.container.register("chineseGreeter", new ChineseGreeter());
		// 曾期望 isInstanceOf(EnglishGreeter.class)（我配置的是英文环境），实际输出：
		// [我配置的是英文环境，应该拿到 EnglishGreeter]
		// Expecting actual: org.springframework.samples.ch01.ChineseGreeter@1f2f9244
		// to be an instance of: org.springframework.samples.ch01.EnglishGreeter
		assertThat(this.container.getBean(Greeter.class)).isInstanceOf(ChineseGreeter.class);
	}

	@Test
	@DisplayName("缺陷 2 的真面目：只改 Bean 的名字，胜出者就换了 —— 结果与意图无关")
	void renamingTheKeyChangesTheWinner() {
		// 与上一个测试完全相同的两个实现、相同的注册顺序，只是名字不同。
		this.container.register("greeterEn", new EnglishGreeter());
		this.container.register("greeterZh", new ChineseGreeter());
		assertThat(this.container.getBean(Greeter.class)).isInstanceOf(EnglishGreeter.class);
	}

}
