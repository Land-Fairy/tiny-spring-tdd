package org.springframework.core.type.classreading;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.core.io.ClassPathResource;
import org.springframework.samples.ch17.probe.Counter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同一件事的两种做法：反射（会初始化类）与读字节码（不会）。加上耗时对照。
 */
class ReflectionVersusAsmTests {

	private static final List<String> CLASS_FILES = List.of("org/springframework/samples/ch17/AuditComponent.class",
			"org/springframework/samples/ch17/GreetingService.class",
			"org/springframework/samples/ch17/OrderRepository.class",
			"org/springframework/samples/ch17/PlainPojo.class",
			"org/springframework/samples/ch17/service/SmsService.class",
			"org/springframework/samples/ch17/probe/InitProbe.class");

	@Test
	@DisplayName("反射读元数据会初始化类，读字节码不会")
	void onlyReflectionInitializesTheClass() {
		Counter.reset();

		new SimpleMetadataReader(new ClassPathResource("org/springframework/samples/ch17/probe/ReflectionProbe.class"))
			.getClassMetadata();
		assertThat(Counter.initialized).isZero();

		new ReflectionMetadataReader("org.springframework.samples.ch17.probe.ReflectionProbe").getClassMetadata();
		assertThat(Counter.initialized).isEqualTo(1);
	}

	@Test
	@DisplayName("读字节码一万次也不加载任何类；而且它并不比反射快——ASM 的收益不是速度")
	void readingBytecodeNeitherLoadsNorIsFaster() {
		Counter.reset();
		long asm = time(() -> {
			for (String file : CLASS_FILES) {
				new SimpleMetadataReader(new ClassPathResource(file)).getClassMetadata();
			}
		});
		// 这条才是本章的论点：600 次读取之后，一个类都没被初始化。
		// 必须在跑反射那一轮之前断言——反射那轮会把它们全加载进来。
		assertThat(Counter.initialized).isZero();

		long reflection = time(() -> {
			for (String file : CLASS_FILES) {
				new ReflectionMetadataReader(classNameOf(file)).getClassMetadata();
			}
		});

		System.out.println(
				"[ch17] 600 次读取：读字节码 " + asm + "ms，反射 " + reflection + "ms；" + "反射之后被初始化的类数 = " + Counter.initialized);
		// 反射那轮至少又点亮了一个类。
		// 具体数量取决于测试执行顺序（同一个类在一个 JVM 里只会初始化一次），所以不写死数字。
		assertThat(Counter.initialized).isGreaterThan(0);
	}

	private long time(Runnable work) {
		long start = System.nanoTime();
		for (int i = 0; i < 100; i++) {
			work.run();
		}
		return (System.nanoTime() - start) / 1_000_000;
	}

	private String classNameOf(String classFile) {
		return classFile.substring(0, classFile.length() - ".class".length()).replace('/', '.');
	}

}
