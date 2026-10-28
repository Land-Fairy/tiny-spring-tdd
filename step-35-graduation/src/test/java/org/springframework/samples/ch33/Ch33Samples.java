package org.springframework.samples.ch33;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * 第 33 章的样本：四个「注定失败」的 Bean。
 *
 * <p>
 * 它们不是为了让容器跑起来，而是为了让容器<b>以四种不同的方式报错</b>—— 本章要读的就是这四种栈。
 */
public final class Ch33Samples {

	private Ch33Samples() {
	}

	/** 环的一半：构造器要 B。 */
	public static class CircularA {

		/**
		 * 参数名必须是 {@code circularB}——本书的构造器解析<b>按参数名取 Bean</b>（第 04 章）， 写成 {@code b}
		 * 会被当成「没有这个名字的 Bean」，环就伪装成「缺依赖」。
		 * @param circularB 环的另一半
		 */
		public CircularA(CircularB circularB) {
			// 只为制造环
		}

	}

	/** 环的另一半：构造器要 A。 */
	public static class CircularB {

		/**
		 * 同 {@code CircularA}：参数名即 Bean 名。
		 * @param circularA 环的另一半
		 */
		public CircularB(CircularA circularA) {
			// 只为制造环
		}

	}

	/** 缺依赖：字段要一个容器里没有的类型。 */
	public static class MissingDependency {

		@Autowired
		private UnregisteredRepository repository;

	}

	/** 容器里根本没有这个类型。 */
	public interface UnregisteredRepository {

	}

	/** 类型不匹配：占位符的值转换不到 int。 */
	public static class WrongType {

		@Value("${ch33.port}")
		private int port;

	}

	/** 初始化回调里抛异常。 */
	public static class Exploding implements InitializingBean {

		@Override
		public void afterPropertiesSet() {
			throw new IllegalStateException("boom in afterPropertiesSet");
		}

	}

}
