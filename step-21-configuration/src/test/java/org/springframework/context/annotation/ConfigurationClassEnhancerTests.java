package org.springframework.context.annotation;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生成器：把 {@code @Bean} 方法覆写成「转给拦截器」的壳。这里直接对着生成类做，不看容器。
 *
 * <p>
 * 覆盖的重点是<b>装箱与拆箱</b>：方法参数要装进 {@code Object[]} 才能交给拦截器，返回值要从 {@code Object} 变回 {@code int}
 * / {@code long} / … 。八个原始类型走八条不同的字节码路径，每条都得有测试。
 */
@DisplayName("配置类增强器")
class ConfigurationClassEnhancerTests {

	@Test
	@DisplayName("引用类型：参数原样传出，返回值直接强转")
	void passesReferencesThrough() throws Exception {
		RecordingInterceptor interceptor = new RecordingInterceptor("canned");
		Shapes shapes = enhanced(Shapes.class, "reference", interceptor);

		assertThat(shapes.reference()).isEqualTo("canned");
		assertThat(interceptor.calls).containsExactly("reference");
		assertThat(interceptor.arguments.get(0)).isEmpty();
	}

	@Test
	@DisplayName("int 参数的装箱与返回值的拆箱")
	void boxesAndUnboxesInt() throws Exception {
		RecordingInterceptor interceptor = new RecordingInterceptor(7);
		Shapes shapes = enhanced(Shapes.class, "anInt", interceptor);

		assertThat(shapes.anInt(41)).isEqualTo(7);
		assertThat(interceptor.arguments.get(0)).containsExactly(41);
	}

	@Test
	@DisplayName("long / double 参数各占两个局部变量槽，不能算错")
	void handlesWideArguments() throws Exception {
		RecordingInterceptor interceptor = new RecordingInterceptor(9L);
		Shapes shapes = enhanced(Shapes.class, "aLong", interceptor);

		assertThat(shapes.aLong(3L, 1.5d)).isEqualTo(9L);
		assertThat(interceptor.arguments.get(0)).containsExactly(3L, 1.5d);
	}

	@Test
	@DisplayName("boolean / short / byte / char / float 各有自己的装箱类")
	void handlesTheOtherPrimitives() throws Exception {
		assertThat(enhanced(Shapes.class, "aBool", new RecordingInterceptor(true)).aBool(false)).isTrue();
		assertThat(enhanced(Shapes.class, "aShort", new RecordingInterceptor((short) 5)).aShort((short) 2))
			.isEqualTo((short) 5);
		assertThat(enhanced(Shapes.class, "aByte", new RecordingInterceptor((byte) 5)).aByte((byte) 2))
			.isEqualTo((byte) 5);
		assertThat(enhanced(Shapes.class, "aChar", new RecordingInterceptor('z')).aChar('a')).isEqualTo('z');
		assertThat(enhanced(Shapes.class, "aFloat", new RecordingInterceptor(2.5f)).aFloat(1.5f)).isEqualTo(2.5f);
	}

	@Test
	@DisplayName("void 方法：结果丢掉就返回")
	void discardsVoidResult() throws Exception {
		RecordingInterceptor interceptor = new RecordingInterceptor(null);
		Class<?> enhancedClass = ConfigurationClassEnhancer.enhance(Shapes.class, List.of(method("nothing")),
				interceptor);

		// 包私有的方法只能用 getDeclaredMethod 找到——getMethod 只认 public
		Method nothing = enhancedClass.getDeclaredMethod("nothing");
		nothing.invoke(enhancedClass.getDeclaredConstructor().newInstance());

		assertThat(interceptor.calls).containsExactly("nothing");
	}

	@Test
	@DisplayName("数组类型：描述符里是 [ 开头，不能写成 Ljava/lang/String;")
	void handlesArrays() throws Exception {
		RecordingInterceptor interceptor = new RecordingInterceptor(new String[] { "canned" });
		Shapes shapes = enhanced(Shapes.class, "strings", interceptor);

		assertThat(shapes.strings(new String[] { "given" })).containsExactly("canned");
		assertThat((String[]) interceptor.arguments.get(0)[0]).containsExactly("given");
	}

	@Test
	@DisplayName("五个 long 参数：局部变量下标超过 5 之后 iconst_ 不够用，要换 bipush")
	void handlesWideArgumentSlots() throws Exception {
		RecordingInterceptor interceptor = new RecordingInterceptor(99L);
		Shapes shapes = enhanced(Shapes.class, "wideSlots", interceptor);

		assertThat(shapes.wideSlots(1L, 2L, 3L, 4L, 5L)).isEqualTo(99L);
		assertThat(interceptor.arguments.get(0)).containsExactly(1L, 2L, 3L, 4L, 5L);
	}

	@Test
	@DisplayName("方法句柄没有绑定实例：同一个生成类的两个对象各自跑自己的方法体")
	void reusesCachedMethodHandleAcrossInstances() throws Exception {
		Method bump = Shapes.class.getDeclaredMethod("bump");
		ConfigMethodInterceptor interceptor = new ConfigMethodInterceptor(null, Shapes.class, Map.of("bump", bump),
				Map.of());
		Class<?> enhancedClass = ConfigurationClassEnhancer.enhance(Shapes.class, List.of(bump), interceptor);
		Shapes first = (Shapes) enhancedClass.getDeclaredConstructor().newInstance();
		Shapes second = (Shapes) enhancedClass.getDeclaredConstructor().newInstance();

		assertThat(first.bump()).isEqualTo(1);
		assertThat(first.bump()).isEqualTo(2);
		// 句柄是按方法名缓存的那一个，但它在 second 上跑的是 second 自己的方法体——说明句柄没绑死第一个实例
		assertThat(second.bump()).isEqualTo(1);
	}

	@Test
	@DisplayName("protected 与包私有方法的覆写要带上对应的访问标志，否则装不上")
	void keepsMethodAccess() throws Exception {
		RecordingInterceptor interceptor = new RecordingInterceptor((short) 1);
		Class<?> enhancedClass = ConfigurationClassEnhancer.enhance(Shapes.class,
				List.of(method("aShort"), method("aByte")), interceptor);

		assertThat(enhancedClass.getDeclaredMethod("aShort", short.class).getModifiers() & 0x0004).isNotZero();
		assertThat(enhancedClass.getDeclaredMethod("aByte", byte.class).getModifiers() & 0x0001).isZero();
	}

	@Test
	@DisplayName("生成类是原类的子类，名字带固定后缀（认不出来就当没增强）")
	void generatedClassIsASubclass() {
		Class<?> enhancedClass = ConfigurationClassEnhancer.enhance(Shapes.class, List.of(method("reference")),
				new RecordingInterceptor("x"));

		assertThat(enhancedClass.getSuperclass()).isEqualTo(Shapes.class);
		assertThat(enhancedClass.getName()).startsWith(Shapes.class.getName() + "$$EnhancedByTinySpring");
	}

	@Test
	@DisplayName("方法体只在第一次调用时找句柄，之后复用（按方法名缓存）")
	void cachesTheMethodHandle() throws Exception {
		RecordingInterceptor interceptor = new RecordingInterceptor(1);
		Shapes shapes = enhanced(Shapes.class, "anInt", interceptor);

		shapes.anInt(1);
		shapes.anInt(2);

		assertThat(interceptor.calls).containsExactly("anInt", "anInt");
	}

	private Shapes enhanced(Class<?> configClass, String methodName, RecordingInterceptor interceptor)
			throws Exception {
		Class<?> enhancedClass = ConfigurationClassEnhancer.enhance(configClass, List.of(method(methodName)),
				interceptor);
		return (Shapes) enhancedClass.getDeclaredConstructor().newInstance();
	}

	private Method method(String name) {
		for (Method candidate : Shapes.class.getDeclaredMethods()) {
			if (candidate.getName().equals(name)) {
				return candidate;
			}
		}
		throw new IllegalArgumentException(name);
	}

	/**
	 * 拦截器的替身：不接容器，只记下被调了什么、参数是什么，然后返回一个事先摆好的值。
	 */
	private static class RecordingInterceptor extends ConfigMethodInterceptor {

		private final Object result;

		private final List<String> calls = new ArrayList<>();

		private final List<Object[]> arguments = new ArrayList<>();

		RecordingInterceptor(Object result) {
			super(null, Shapes.class, new LinkedHashMap<>(), new LinkedHashMap<>());
			this.result = result;
		}

		@Override
		Object doInvoke(Object enhanced, String methodName, Object[] args) {
			this.calls.add(methodName);
			this.arguments.add(args);
			return this.result;
		}

	}

	/**
	 * 各种形状的方法：本章的生成器只处理「无分支」的方法体，所以参数与返回值可以随便，方法体内容无所谓。
	 */
	static class Shapes {

		private int state;

		String reference() {
			return "real";
		}

		int anInt(int value) {
			return value;
		}

		long aLong(long value, double other) {
			return value + (long) other;
		}

		boolean aBool(boolean value) {
			return value;
		}

		protected short aShort(short value) {
			return value;
		}

		byte aByte(byte value) {
			return value;
		}

		char aChar(char value) {
			return value;
		}

		float aFloat(float value) {
			return value;
		}

		String[] strings(String[] values) {
			return values;
		}

		long wideSlots(long a, long b, long c, long d, long e) {
			return a + b + c + d + e;
		}

		int bump() {
			return ++this.state;
		}

		void nothing() {
		}

		@Override
		public String toString() {
			return "Shapes";
		}

	}

}
