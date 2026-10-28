package org.springframework.core.convert.support;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.ConversionService;
import org.springframework.samples.ch23.Level;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DefaultConversionService：内置转换器做了什么、没做什么")
class ConversionServiceTests {

	private final ConversionService service = new DefaultConversionService();

	@Test
	@DisplayName("字符串转八种数值类型，基本类型与包装类型一视同仁")
	void textToNumbers() {
		assertThat(this.service.convert("42", Integer.class)).isEqualTo(42);
		assertThat(this.service.convert("42", int.class)).isEqualTo(42);
		assertThat(this.service.convert("9000000000", Long.class)).isEqualTo(9000000000L);
		assertThat(this.service.convert("9000000000", long.class)).isEqualTo(9000000000L);
		assertThat(this.service.convert("7", Short.class)).isEqualTo((short) 7);
		assertThat(this.service.convert("3", Byte.class)).isEqualTo((byte) 3);
		assertThat(this.service.convert("1.5", Double.class)).isEqualTo(1.5d);
		assertThat(this.service.convert("2.5", Float.class)).isEqualTo(2.5f);
		assertThat(this.service.convert("12.50", BigDecimal.class)).isEqualTo(new BigDecimal("12.50"));
	}

	@Test
	@DisplayName("字符串转布尔：只认白名单，且大小写与空白都不计较")
	void textToBoolean() {
		assertThat(this.service.convert("true", Boolean.class)).isTrue();
		assertThat(this.service.convert("ON", boolean.class)).isTrue();
		assertThat(this.service.convert(" Yes ", Boolean.class)).isTrue();
		assertThat(this.service.convert("0", Boolean.class)).isFalse();
		assertThat(this.service.convert("off", Boolean.class)).isFalse();
	}

	@Test
	@DisplayName("字符串转字符：长度必须是 1")
	void textToCharacter() {
		assertThat(this.service.convert("x", Character.class)).isEqualTo('x');
		assertThat(this.service.convert("x", char.class)).isEqualTo('x');
	}

	@Test
	@DisplayName("字符串转枚举：精确匹配，大小写敏感")
	void textToEnum() {
		assertThat(this.service.convert("DEBUG", Level.class)).isEqualTo(Level.DEBUG);
		assertThat(this.service.canConvert(String.class, Level.class)).isTrue();
	}

	@Test
	@DisplayName("数字转数字：包装类型之间没有继承关系，全靠这一族转换器")
	void numberToNumber() {
		assertThat(this.service.convert(42, Long.class)).isEqualTo(42L);
		assertThat(this.service.convert(42, Double.class)).isEqualTo(42d);
		assertThat(this.service.convert(1.9d, Integer.class)).isEqualTo(1);
		assertThat(this.service.convert(42L, BigDecimal.class)).isEqualTo(new BigDecimal("42"));
		assertThat(this.service.canConvert(Integer.class, Long.class)).isTrue();
	}

	@Test
	@DisplayName("任何对象都能变成字符串")
	void anythingToString() {
		assertThat(this.service.convert(42, String.class)).isEqualTo("42");
		assertThat(this.service.convert(Level.INFO, String.class)).isEqualTo("INFO");
		assertThat(this.service.convert(new BigDecimal("1.50"), String.class)).isEqualTo("1.50");
	}

	@Test
	@DisplayName("目标类型本来就能装下源类型时原样返回，不需要任何转换器")
	void identityConversion() {
		Object value = new Object();

		assertThat(this.service.canConvert(String.class, Object.class)).isTrue();
		assertThat(this.service.convert("text", Object.class)).isSameAs("text");
		assertThat(this.service.convert(value, Object.class)).isSameAs(value);
		assertThat(this.service.canConvert(String.class, String.class)).isTrue();
	}

	@Test
	@DisplayName("空串与首尾空白：空串归 null，空白先 trim")
	void blankAndEmptyText() {
		assertThat(this.service.convert("  42  ", Integer.class)).isEqualTo(42);
		assertThat(this.service.convert("  ", Integer.class)).isNull();
		assertThat(this.service.convert("", Level.class)).isNull();
	}

	@Test
	@DisplayName("null 值：引用类型得 null，基本类型当场失败")
	void nullsAreOnlyAllowedForReferences() {
		assertThat(this.service.convert(null, Integer.class)).isNull();
	}

}
