package org.springframework.core.convert.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.ConverterNotFoundException;
import org.springframework.samples.ch23.Level;
import org.springframework.samples.ch23.Money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@DisplayName("转换失败：两种病要分得开，消息要带得出源值")
class ConversionFailureTests {

	private final ConversionService service = new DefaultConversionService();

	@Test
	@DisplayName("有转换器但值不合法：抛 ConversionFailedException，消息里有源值和目标类型")
	void failedConversionCarriesValueAndTargetType() {
		ConversionFailedException ex = catchThrowableOfType(() -> this.service.convert("abc", Integer.class),
				ConversionFailedException.class);

		assertThat(ex.getSource()).isEqualTo("abc");
		assertThat(ex.getSourceType()).isEqualTo(String.class);
		assertThat(ex.getTargetType()).isEqualTo(Integer.class);
		assertThat(ex.getMessage()).contains("abc").contains("java.lang.Integer");
	}

	@Test
	@DisplayName("没人会转：抛 ConverterNotFoundException，而不是执行期才炸")
	void noConverterIsADifferentException() {
		assertThatExceptionOfType(ConverterNotFoundException.class)
			.isThrownBy(() -> this.service.convert(new Object(), Money.class))
			.withMessageContaining("No converter found")
			.withMessageContaining("Money");
	}

	@Test
	@DisplayName("canConvert 与 convert 必须一致：说不转，转的时候也要拒绝")
	void canConvertAgreesWithConvert() {
		assertThat(this.service.canConvert(String.class, Money.class)).isFalse();
		assertThatExceptionOfType(ConverterNotFoundException.class)
			.isThrownBy(() -> this.service.convert("12.50 CNY", Money.class));
	}

	@Test
	@DisplayName("枚举名写错：转换失败，消息带原始值")
	void invalidEnumName() {
		assertThatExceptionOfType(ConversionFailedException.class)
			.isThrownBy(() -> this.service.convert("debug", Level.class))
			.withMessageContaining("debug");
	}

	@Test
	@DisplayName("null 给基本类型：转换阶段就拒绝，不能等到拆箱时 NPE")
	void nullToPrimitiveFailsAtConversionTime() {
		assertThatExceptionOfType(ConversionFailedException.class)
			.isThrownBy(() -> this.service.convert(null, int.class))
			.withMessageContaining("primitive");
	}

}
