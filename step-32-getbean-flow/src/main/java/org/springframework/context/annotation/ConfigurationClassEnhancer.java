package org.springframework.context.annotation;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 给一个 {@code @Configuration} 类生成子类，把每个 {@code @Bean} 方法改成「先去容器要，没有才真跑方法体」。
 *
 * <p>
 * <b>为什么非得生成子类</b>：full 模式要拦的是配置类里的一行普通 Java 调用 {@code repository()}。这一行在字节码里是
 * {@code invokevirtual}，走的是<b>虚分派</b>—— 也就是说，只要配置类实例的真实类型有一个覆写了该方法的子类，这行调用就会落到子类方法上。
 * 反射做不到这件事（反射只能<b>发</b>一个调用，不能<b>接</b>一个调用），所以唯一的入口是造一个子类。 真实 Spring 用 CGLIB
 * 造这个子类；我们不引第三方库，自己写一个最小的类文件生成器。
 *
 * <p>
 * <b>生成出来的东西长这样</b>（假设配置类 {@code AppConfig} 有一个 {@code @Bean Repository repository()}）：
 *
 * <pre>{@code
 * public class AppConfig$$EnhancedByTinySpring1 extends AppConfig {
 *     public Repository repository() {
 *         return (Repository) ConfigMethodInterceptor.invoke(this, "repository", new Object[0]);
 *     }
 * }
 * }</pre>
 *
 * <p>
 * 就这些。没有分支、没有循环、没有字段、没有 StackMapTable——所以生成器的代码量能压到两百行。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.context.annotation.ConfigurationClassEnhancer}（287
 * 行）与 {@code ConfigurationClassEnhancer.BeanMethodInterceptor}（{@code :233-287}）。它的生成器是
 * CGLIB 的 {@code Enhancer}，回调是 {@code BeanMethodInterceptor} 与
 * {@code BeanFactoryAwareMethodInterceptor}； 我们的 {@link ConfigMethodInterceptor}
 * 就是后者的极简版。
 *
 * @see ConfigMethodInterceptor
 * @see ConfigurationClassPostProcessor
 */
final class ConfigurationClassEnhancer {

	/**
	 * 生成类 → 拦截器。生成的类没有字段，所以拦截器只能从这个表里按类反查。
	 */
	private static final Map<Class<?>, ConfigMethodInterceptor> INTERCEPTORS = new ConcurrentHashMap<>();

	/**
	 * 生成类名字里的序号：同一个配置类被两个容器增强时必须是两个不同的类，否则第二次 {@code defineClass} 会撞名。
	 */
	private static final AtomicInteger SEQUENCE = new AtomicInteger();

	/**
	 * 「Code」这个属性名的常量池下标。它必须先进常量池，而常量池要等所有 entry 就位才能写——所以这里先占一个位置： {@link ConstantPool}
	 * 的第一个 entry 固定是字符串 {@code "Code"}。
	 */
	private static final int CODE_ATTRIBUTE_NAME_INDEX = 1;

	private ConfigurationClassEnhancer() {
	}

	/**
	 * 给配置类生成增强子类。
	 * @param configClass 原配置类
	 * @param beanMethods 需要拦截的方法（就是 {@code @Bean} 方法）
	 * @param interceptor 拦截器，生成类里的每次调用都会回到它
	 * @return 生成出来的子类
	 */
	static Class<?> enhance(Class<?> configClass, List<Method> beanMethods, ConfigMethodInterceptor interceptor) {
		String enhancedName = configClass.getName() + "$$EnhancedByTinySpring" + SEQUENCE.incrementAndGet();
		byte[] bytes = writeClass(enhancedName, configClass, beanMethods);
		try {
			// 在原类的「包」里定义，这样包私有方法、包私有构造器都还够得着
			Class<?> enhancedClass = MethodHandles.privateLookupIn(configClass, MethodHandles.lookup())
				.defineClass(bytes);
			INTERCEPTORS.put(enhancedClass, interceptor);
			return enhancedClass;
		}
		catch (IllegalAccessException | LinkageError ex) {
			throw new IllegalStateException("Failed to enhance configuration class [" + configClass.getName() + "]",
					ex);
		}
	}

	/**
	 * 按生成类反查拦截器。生成类里的方法就是靠它找到「该问哪个容器」。
	 * @param enhancedClass 生成类
	 * @return 拦截器
	 */
	static ConfigMethodInterceptor interceptorFor(Class<?> enhancedClass) {
		ConfigMethodInterceptor interceptor = INTERCEPTORS.get(enhancedClass);
		if (interceptor == null) {
			throw new IllegalStateException(
					"No interceptor registered for enhanced class [" + enhancedClass.getName() + "]");
		}
		return interceptor;
	}

	// ------------------------------------------------------------------
	// 最小类文件生成器：只支持「覆写若干个无分支方法」这一种形状
	// ------------------------------------------------------------------

	private static byte[] writeClass(String enhancedName, Class<?> configClass, List<Method> beanMethods) {
		String enhancedInternalName = internalName(enhancedName);
		ConstantPool pool = new ConstantPool();
		int thisClassIndex = pool.classRef(enhancedInternalName);
		int superClassIndex = pool.classRef(internalName(configClass.getName()));
		int objectClassIndex = pool.classRef("java/lang/Object");
		int interceptorClassIndex = pool.classRef("org/springframework/context/annotation/ConfigMethodInterceptor");
		int invokeIndex = pool.methodRef(interceptorClassIndex, pool.nameAndType("invoke",
				"(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;"));
		int superInitIndex = pool.methodRef(superClassIndex, pool.nameAndType("<init>", "()V"));

		List<byte[]> methods = new ArrayList<>();
		methods.add(defaultConstructor(pool, superInitIndex));
		for (Method beanMethod : beanMethods) {
			methods.add(beanMethodOverride(pool, beanMethod, objectClassIndex, invokeIndex));
		}

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeInt(0xCAFEBABE);
			out.writeShort(0);
			out.writeShort(52); // Java 8 的类文件版本
			pool.writeTo(out);
			out.writeShort(0x0021); // ACC_PUBLIC | ACC_SUPER
			out.writeShort(thisClassIndex);
			out.writeShort(superClassIndex);
			out.writeShort(0); // 不实现接口
			out.writeShort(0); // 没有字段
			out.writeShort(methods.size());
			for (byte[] method : methods) {
				out.write(method);
			}
			out.writeShort(0); // 没有类级属性
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return bytes.toByteArray();
	}

	private static byte[] defaultConstructor(ConstantPool pool, int superInitIndex) {
		ByteArrayOutputStream code = new ByteArrayOutputStream();
		code.write(0x2a); // aload_0
		code.write(0xb7); // invokespecial
		writeShort(code, superInitIndex);
		code.write(0xb1); // return
		return methodInfo(pool.utf8("<init>"), pool.utf8("()V"), 0x0001, new MethodCode(code.toByteArray(), 1, 1));
	}

	private static byte[] beanMethodOverride(ConstantPool pool, Method beanMethod, int objectClassIndex,
			int invokeIndex) {
		Class<?>[] parameterTypes = beanMethod.getParameterTypes();
		Class<?> returnType = beanMethod.getReturnType();
		ByteArrayOutputStream code = new ByteArrayOutputStream();
		code.write(0x2a); // aload_0
		code.write(0x13); // ldc_w
		writeShort(code, pool.string(beanMethod.getName()));
		pushInt(code, parameterTypes.length);
		code.write(0xbd); // anewarray
		writeShort(code, objectClassIndex);
		int slot = 1;
		for (int i = 0; i < parameterTypes.length; i++) {
			code.write(0x59); // dup
			pushInt(code, i);
			code.write(loadOpcode(parameterTypes[i]));
			code.write(slot);
			if (parameterTypes[i].isPrimitive()) {
				int boxed = pool.methodRef(pool.classRef(internalName(boxTypeName(parameterTypes[i]))),
						pool.nameAndType("valueOf", "(" + descriptor(parameterTypes[i]) + ")L"
								+ internalName(boxTypeName(parameterTypes[i])) + ";"));
				code.write(0xb8); // invokestatic
				writeShort(code, boxed);
			}
			code.write(0x53); // aastore
			slot += (parameterTypes[i] == long.class || parameterTypes[i] == double.class) ? 2 : 1;
		}
		code.write(0xb8); // invokestatic
		writeShort(code, invokeIndex);
		byte[] returnCode = returnCode(pool, returnType);
		code.write(returnCode, 0, returnCode.length);
		int maxStack = Math.max(6, 4 + 2 * parameterTypes.length);
		return methodInfo(pool.utf8(beanMethod.getName()), pool.utf8(descriptorOf(beanMethod)), accessOf(beanMethod),
				new MethodCode(code.toByteArray(), maxStack, slot));
	}

	private static byte[] returnCode(ConstantPool pool, Class<?> returnType) {
		if (returnType == void.class) {
			return new byte[] { 0x57, (byte) 0xb1 }; // pop, return
		}
		ByteArrayOutputStream code = new ByteArrayOutputStream();
		code.write(0xc0); // checkcast
		if (returnType.isPrimitive()) {
			writeShort(code, pool.classRef(internalName(boxTypeName(returnType))));
			String methodName = unboxMethodName(returnType);
			String signature = "()" + descriptor(returnType);
			code.write(0xb6); // invokevirtual
			writeShort(code, pool.methodRef(pool.classRef(internalName(boxTypeName(returnType))),
					pool.nameAndType(methodName, signature)));
			code.write(returnOpcode(returnType));
		}
		else {
			writeShort(code, pool.classRef(internalName(returnType.getName())));
			code.write(0xb0); // areturn
		}
		return code.toByteArray();
	}

	private static byte[] methodInfo(int nameIndex, int descriptorIndex, int access, MethodCode methodCode) {
		ByteArrayOutputStream attribute = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(attribute)) {
			out.writeShort(methodCode.maxStack());
			out.writeShort(methodCode.maxLocals());
			out.writeInt(methodCode.bytes().length);
			out.write(methodCode.bytes());
			out.writeShort(0); // 没有异常表
			out.writeShort(0); // 没有方法级属性
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		ByteArrayOutputStream method = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(method)) {
			out.writeShort(access);
			out.writeShort(nameIndex);
			out.writeShort(descriptorIndex);
			out.writeShort(1); // 一个属性：Code
			out.writeShort(CODE_ATTRIBUTE_NAME_INDEX);
			out.writeInt(attribute.size());
			out.write(attribute.toByteArray());
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return method.toByteArray();
	}

	private static void pushInt(ByteArrayOutputStream code, int value) {
		if (value >= 0 && value <= 5) {
			code.write(0x03 + value); // iconst_<n>
		}
		else if (value >= -128 && value <= 127) {
			code.write(0x10); // bipush
			code.write(value);
		}
		else {
			code.write(0x11); // sipush
			code.write((value >> 8) & 0xFF);
			code.write(value & 0xFF);
		}
	}

	private static int loadOpcode(Class<?> type) {
		if (type == long.class) {
			return 0x16; // lload
		}
		if (type == float.class) {
			return 0x17; // fload
		}
		if (type == double.class) {
			return 0x18; // dload
		}
		return type.isPrimitive() ? 0x15 : 0x19; // iload / aload
	}

	private static int returnOpcode(Class<?> type) {
		if (type == long.class) {
			return 0xad; // lreturn
		}
		if (type == float.class) {
			return 0xae; // freturn
		}
		if (type == double.class) {
			return 0xaf; // dreturn
		}
		return 0xac; // ireturn
	}

	private static String boxTypeName(Class<?> type) {
		if (type == int.class) {
			return "java.lang.Integer";
		}
		if (type == long.class) {
			return "java.lang.Long";
		}
		if (type == boolean.class) {
			return "java.lang.Boolean";
		}
		if (type == double.class) {
			return "java.lang.Double";
		}
		if (type == float.class) {
			return "java.lang.Float";
		}
		if (type == short.class) {
			return "java.lang.Short";
		}
		if (type == byte.class) {
			return "java.lang.Byte";
		}
		if (type == char.class) {
			return "java.lang.Character";
		}
		throw new IllegalArgumentException("Not a primitive type: " + type);
	}

	private static String unboxMethodName(Class<?> type) {
		if (type == int.class) {
			return "intValue";
		}
		if (type == long.class) {
			return "longValue";
		}
		if (type == boolean.class) {
			return "booleanValue";
		}
		if (type == double.class) {
			return "doubleValue";
		}
		if (type == float.class) {
			return "floatValue";
		}
		if (type == short.class) {
			return "shortValue";
		}
		if (type == byte.class) {
			return "byteValue";
		}
		return "charValue";
	}

	private static int accessOf(Method method) {
		int modifiers = method.getModifiers();
		if (Modifier.isPublic(modifiers)) {
			return 0x0001;
		}
		if (Modifier.isProtected(modifiers)) {
			return 0x0004;
		}
		return 0x0000;
	}

	private static String descriptorOf(Method method) {
		StringBuilder descriptor = new StringBuilder("(");
		for (Class<?> parameterType : method.getParameterTypes()) {
			descriptor.append(descriptor(parameterType));
		}
		return descriptor.append(')').append(descriptor(method.getReturnType())).toString();
	}

	private static String descriptor(Class<?> type) {
		if (type == void.class) {
			return "V";
		}
		if (!type.isPrimitive()) {
			return type.isArray() ? internalName(type.getName()) : "L" + internalName(type.getName()) + ";";
		}
		if (type == int.class) {
			return "I";
		}
		if (type == long.class) {
			return "J";
		}
		if (type == boolean.class) {
			return "Z";
		}
		if (type == double.class) {
			return "D";
		}
		if (type == float.class) {
			return "F";
		}
		if (type == short.class) {
			return "S";
		}
		if (type == byte.class) {
			return "B";
		}
		return "C";
	}

	private static String internalName(String className) {
		return className.replace('.', '/');
	}

	private static void writeShort(ByteArrayOutputStream out, int value) {
		out.write((value >> 8) & 0xFF);
		out.write(value & 0xFF);
	}

	/**
	 * 常量池生成器：按需追加，重复的字符串复用同一个下标。
	 */
	private static final class ConstantPool {

		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		private final DataOutputStream out = new DataOutputStream(this.bytes);

		private final Map<String, Integer> cache = new HashMap<>();

		private int count = 1;

		ConstantPool() {
			// 固定第一个 entry 是 "Code"：属性名的下标必须能被 #CODE_ATTRIBUTE_NAME_INDEX 引用
			int index = utf8("Code");
			if (index != CODE_ATTRIBUTE_NAME_INDEX) {
				throw new IllegalStateException("The \"Code\" entry must be the first constant pool entry");
			}
		}

		int utf8(String value) {
			String key = "U:" + value;
			Integer known = this.cache.get(key);
			if (known != null) {
				return known;
			}
			int index = this.count++;
			write(1);
			try {
				this.out.writeUTF(value); // 与 CONSTANT_Utf8 的格式一致：u2 长度 + 改良 UTF-8
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
			this.cache.put(key, index);
			return index;
		}

		int classRef(String internalName) {
			String key = "C:" + internalName;
			Integer known = this.cache.get(key);
			if (known != null) {
				return known;
			}
			int nameIndex = utf8(internalName);
			int index = this.count++;
			write(7);
			writeShort(this.bytes, nameIndex);
			this.cache.put(key, index);
			return index;
		}

		int string(String value) {
			String key = "S:" + value;
			Integer known = this.cache.get(key);
			if (known != null) {
				return known;
			}
			int utf8Index = utf8(value);
			int index = this.count++;
			write(8);
			writeShort(this.bytes, utf8Index);
			this.cache.put(key, index);
			return index;
		}

		int nameAndType(String name, String descriptor) {
			String key = "N:" + name + ":" + descriptor;
			Integer known = this.cache.get(key);
			if (known != null) {
				return known;
			}
			int nameIndex = utf8(name);
			int descriptorIndex = utf8(descriptor);
			int index = this.count++;
			write(12);
			writeShort(this.bytes, nameIndex);
			writeShort(this.bytes, descriptorIndex);
			this.cache.put(key, index);
			return index;
		}

		int methodRef(int classIndex, int nameAndTypeIndex) {
			String key = "M:" + classIndex + ":" + nameAndTypeIndex;
			Integer known = this.cache.get(key);
			if (known != null) {
				return known;
			}
			int index = this.count++;
			write(10);
			writeShort(this.bytes, classIndex);
			writeShort(this.bytes, nameAndTypeIndex);
			this.cache.put(key, index);
			return index;
		}

		void writeTo(DataOutputStream target) throws IOException {
			target.writeShort(this.count);
			target.write(this.bytes.toByteArray());
		}

		private void write(int tag) {
			this.bytes.write(tag);
		}

	}

	/**
	 * 一个方法体的三件套：字节码、栈深、局部变量槽数。打包成一个记录只是为了不让 {@code methodInfo} 收五个以上的参数。
	 *
	 * @param bytes 字节码
	 * @param maxStack 最大栈深
	 * @param maxLocals 局部变量槽数（含 {@code this}）
	 */
	private record MethodCode(byte[] bytes, int maxStack, int maxLocals) {
	}

}
