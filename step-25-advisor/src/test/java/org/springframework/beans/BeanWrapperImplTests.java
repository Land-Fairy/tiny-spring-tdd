package org.springframework.beans;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BeanWrapperImpl：把「属性名 → 值」写进一个已经存在的对象")
class BeanWrapperImplTests {

	private Target target;

	private BeanWrapperImpl wrapper;

	@BeforeEach
	void setUp() {
		this.target = new Target();
		this.wrapper = new BeanWrapperImpl(this.target);
	}

	@Test
	@DisplayName("引用类型：直接调用 setter")
	void setsReferenceProperty() {
		this.wrapper.setPropertyValue(new PropertyValue("prefix", "Hi, "));

		assertThat(this.target.getPrefix()).isEqualTo("Hi, ");
	}

	@Test
	@DisplayName("基本类型：装箱后可以填进去（Integer -> int）")
	void setsPrimitivePropertyByAutoboxing() {
		this.wrapper.setPropertyValue(new PropertyValue("repeat", 3));

		assertThat(this.target.getRepeat()).isEqualTo(3);
	}

	@Test
	@DisplayName("null 填引用类型是合法的——很多 setter 就靠 null 表示「没配置」")
	void nullToReferencePropertyIsAccepted() {
		this.wrapper.setPropertyValue(new PropertyValue("prefix", null));

		assertThat(this.target.getPrefix()).isNull();
	}

	@Test
	@DisplayName("null 填基本类型必须报错：int 没有 null 这个值，硬塞只会静默变成 0")
	void nullToPrimitivePropertyIsRejected() {
		assertThatThrownBy(() -> this.wrapper.setPropertyValue(new PropertyValue("repeat", null)))
			.isInstanceOf(TypeMismatchException.class)
			.hasMessageContaining("repeat")
			.hasMessageContaining("int")
			.hasMessageContaining("null");
	}

	@Test
	@DisplayName("类型不兼容要报错，而且要说清「给的是什么、要的是什么」")
	void incompatibleTypeIsRejected() {
		assertThatThrownBy(() -> this.wrapper.setPropertyValue(new PropertyValue("repeat", "3")))
			.isInstanceOf(TypeMismatchException.class)
			.hasMessageContaining("java.lang.String")
			.hasMessageContaining("int");
	}

	@Test
	@DisplayName("属性名写错要报错，不能静默跳过")
	void unknownPropertyIsRejected() {
		assertThatThrownBy(() -> this.wrapper.setPropertyValue(new PropertyValue("presfix", "Hi")))
			.isInstanceOf(NotWritablePropertyException.class)
			.hasMessageContaining("presfix");
	}

	@Test
	@DisplayName("只有 getter 没有 setter 也算不可写")
	void readOnlyPropertyIsRejected() {
		assertThatThrownBy(() -> this.wrapper.setPropertyValue(new PropertyValue("computed", "x")))
			.isInstanceOf(NotWritablePropertyException.class)
			.hasMessageContaining("computed");
	}

	@Test
	@DisplayName("Wrapper 知道自己包着谁")
	void exposesWrappedInstance() {
		assertThat(this.wrapper.getWrappedInstance()).isSameAs(this.target);
		assertThat(this.wrapper.getWrappedClass()).isEqualTo(Target.class);
	}

	/**
	 * 本章的被填充对象。故意用「有 setter 的普通 JavaBean」：不是 final 字段、不走构造器。
	 *
	 * <p>
	 * 注意所有访问器都是 <b>{@code public}</b>。这不是随手写的：{@code java.beans.Introspector} 只扫 public
	 * 方法，包私有的 setter 会被报成「属性不可写」。改成 public 之前，本测试类得到的是
	 * {@code NotWritablePropertyException}——这个坑在本章第 2.3 节有完整记录。
	 */
	public static class Target {

		private String prefix;

		private int repeat;

		public String getPrefix() {
			return this.prefix;
		}

		public void setPrefix(String prefix) {
			this.prefix = prefix;
		}

		public int getRepeat() {
			return this.repeat;
		}

		public void setRepeat(int repeat) {
			this.repeat = repeat;
		}

		public String getComputed() {
			return this.prefix + this.repeat;
		}

	}

}
