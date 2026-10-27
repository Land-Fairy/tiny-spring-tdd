package org.springframework.samples.ch23;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.config.PropertySourcesPlaceholderConfigurer;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.core.env.MapPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@DisplayName("自定义转换器：从登记到 @Value 生效")
class CustomConverterTests {

	@Test
	@DisplayName("登记之后 canConvert 说能转，convert 就真的转得出来")
	void registeredConverterIsUsable() {
		ConversionService service = serviceWithMoney();

		assertThat(service.canConvert(String.class, Money.class)).isTrue();
		assertThat(service.convert("12.50 CNY", Money.class)).hasToString("12.50 CNY");
	}

	@Test
	@DisplayName("登记一个转换器不影响已有转换")
	void registeringDoesNotDisturbBuiltIns() {
		ConversionService service = serviceWithMoney();

		assertThat(service.convert("8080", Integer.class)).isEqualTo(8080);
		assertThat(service.convert("DEBUG", Level.class)).isEqualTo(Level.DEBUG);
	}

	@Test
	@DisplayName("自定义转换器抛异常时，源值一路带到最外层")
	void failingCustomConverterKeepsTheSourceValue() {
		ConversionService service = serviceWithMoney();

		ConversionFailedException ex = catchThrowableOfType(() -> service.convert("12.50", Money.class),
				ConversionFailedException.class);

		assertThat(ex.getSource()).isEqualTo("12.50");
		assertThat(ex.getMessage()).contains("12.50").contains("Money");
	}

	@Test
	@DisplayName("@Value 注入自定义类型：换掉容器的转换服务就生效")
	void valueInjectionUsesTheContainerWideConversionService() {
		try (GenericApplicationContext context = context(serviceWithMoney())) {
			context.refresh();

			assertThat(context.getBean("priceHolder", PriceHolder.class).getPrice()).hasToString("12.50 CNY");
		}
	}

	@Test
	@DisplayName("不换服务时，@Value 注入自定义类型当场失败，消息点名字段")
	void withoutTheConverterTheInjectionFails() {
		GenericApplicationContext context = context(null);

		assertThatExceptionOfType(BeanCreationException.class).isThrownBy(context::refresh)
			.withStackTraceContaining("price");
	}

	private ConversionService serviceWithMoney() {
		DefaultConversionService service = new DefaultConversionService();
		service.addConverter(String.class, Money.class, new MoneyConverter());
		return service;
	}

	private GenericApplicationContext context(ConversionService conversionService) {
		GenericApplicationContext context = new GenericApplicationContext("ch23-sample");
		context.getEnvironment()
			.getPropertySources()
			.addLast(new MapPropertySource("test", Map.of("price", "12.50 CNY")));
		context.addBeanFactoryPostProcessor(new PropertySourcesPlaceholderConfigurer(context.getEnvironment()));
		GenericBeanDefinition definition = new GenericBeanDefinition();
		definition.setBeanClass(PriceHolder.class);
		ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
		((DefaultListableBeanFactory) beanFactory).registerBeanDefinition("priceHolder", definition);
		if (conversionService != null) {
			beanFactory.setConversionService(conversionService);
		}
		beanFactory.addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor(beanFactory));
		return context;
	}

}
