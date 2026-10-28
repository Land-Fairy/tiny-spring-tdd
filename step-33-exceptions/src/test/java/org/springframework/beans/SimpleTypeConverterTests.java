package org.springframework.beans;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@DisplayName("SimpleTypeConverter：文本到目标类型，转不了就响")
class SimpleTypeConverterTests {

	private final SimpleTypeConverter converter = new SimpleTypeConverter();

	@Test
	@DisplayName("八种基本类型与包装类型都能从字符串转出来")
	void numericAndBooleanTypes() {
		assertThat(this.converter.convert("42", int.class, "n", Fixture.class)).isEqualTo(42);
		assertThat(this.converter.convert("42", Integer.class, "n", Fixture.class)).isEqualTo(42);
		assertThat(this.converter.convert("9000000000", long.class, "n", Fixture.class)).isEqualTo(9000000000L);
		assertThat(this.converter.convert("1.5", double.class, "n", Fixture.class)).isEqualTo(1.5);
		assertThat(this.converter.convert("2.5", float.class, "n", Fixture.class)).isEqualTo(2.5f);
		assertThat(this.converter.convert("7", short.class, "n", Fixture.class)).isEqualTo((short) 7);
		assertThat(this.converter.convert("3", byte.class, "n", Fixture.class)).isEqualTo((byte) 3);
		assertThat(this.converter.convert("true", boolean.class, "n", Fixture.class)).isEqualTo(true);
		assertThat(this.converter.convert("x", char.class, "n", Fixture.class)).isEqualTo('x');
	}

	@Test
	@DisplayName("本来就是目标类型（或它的子类型）时原样返回，不做多余动作")
	void alreadyAssignableValuesPassThrough() {
		Object value = new Fixture();

		assertThat(this.converter.convert(value, Fixture.class, "f", Fixture.class)).isSameAs(value);
		assertThat(this.converter.convert(value, Object.class, "f", Fixture.class)).isSameAs(value);
	}

	@Test
	@DisplayName("null 给包装类型得到 null，给基本类型当场失败")
	void nullOnlyFitsNonPrimitives() {
		assertThat(this.converter.convert(null, Integer.class, "n", Fixture.class)).isNull();

		assertThatExceptionOfType(TypeMismatchException.class)
			.isThrownBy(() -> this.converter.convert(null, int.class, "n", Fixture.class))
			.withMessageContaining("cannot assign null to primitive type 'int'");
	}

	@Test
	@DisplayName("转不了就抛 TypeMismatchException，并把「哪个字段、哪个类」写进消息")
	void unconvertibleTextFails() {
		assertThatExceptionOfType(TypeMismatchException.class)
			.isThrownBy(() -> this.converter.convert("abc", int.class, "port", Fixture.class))
			.withMessageContaining("for property 'port'")
			.withMessageContaining(Fixture.class.getName());

		assertThatExceptionOfType(TypeMismatchException.class)
			.isThrownBy(() -> this.converter.convert("SAFE", Missing.class, "mode", Fixture.class))
			.withMessageContaining("to required type '" + Missing.class.getName() + "'");
	}

	@Test
	@DisplayName("Class 走类名查找；找不到也归为类型不匹配")
	void classNamesAreResolved() {
		assertThat(this.converter.convert(Fixture.class.getName(), Class.class, "type", Fixture.class))
			.isEqualTo(Fixture.class);

		assertThatExceptionOfType(TypeMismatchException.class)
			.isThrownBy(() -> this.converter.convert("no.such.Type", Class.class, "type", Fixture.class));
	}

	/** 只用来给错误消息提供「声明类」。 */
	private static class Fixture {

	}

	/** 不是枚举，用来验证「转不了」。 */
	private static class Missing {

	}

}
