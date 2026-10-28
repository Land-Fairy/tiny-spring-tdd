package org.springframework.core.type.classreading;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.core.io.Resource;

/**
 * 直接读 {@code .class} 字节码，回答「这个类叫什么、头上有哪些注解」。
 *
 * <p>
 * 这就是「ASM 路线」的手写版：ASM 是一个字节码操作库，本书不引入依赖， 而是<b>自己按 class 文件格式一步一步走</b>——因为扫描只需要文件里很浅的一层，
 * 走完这一层，你就再也不会觉得「不加载类怎么知道注解」是个魔法。
 *
 * <p>
 * class 文件结构（JVMS §4，只列本书用到的部分）： <pre>
 * magic(4) minor(2) major(2)
 * constant_pool_count(2) constant_pool[...]      ← 所有名字/描述符都在这里
 * access_flags(2) this_class(2) super_class(2) interfaces_count(2) interfaces[...]
 * fields_count(2) field_info[...]                ← 每个字段带自己的属性
 * methods_count(2) method_info[...]
 * attributes_count(2) attribute_info[...]        ← RuntimeVisibleAnnotations 在这里
 * </pre>
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code SimpleMetadataReader}（用 ASM 的 {@code ClassReader} +
 * {@code AnnotationMetadataReadingVisitor}）。真实版还处理内部类、接口、方法注解、 {@code @Conditional}
 * 的嵌套值等；本书只保留「类名 + 类上的注解 + 字符串属性」。
 */
public class SimpleMetadataReader implements MetadataReader {

	private static final int CONSTANT_UTF8 = 1;

	private static final int CONSTANT_INTEGER = 3;

	private static final int CONSTANT_FLOAT = 4;

	private static final int CONSTANT_LONG = 5;

	private static final int CONSTANT_DOUBLE = 6;

	private static final int CONSTANT_CLASS = 7;

	private static final int CONSTANT_STRING = 8;

	private static final int CONSTANT_FIELDREF = 9;

	private static final int CONSTANT_METHODREF = 10;

	private static final int CONSTANT_INTERFACE_METHODREF = 11;

	private static final int CONSTANT_NAME_AND_TYPE = 12;

	private static final int CONSTANT_METHOD_HANDLE = 15;

	private static final int CONSTANT_METHOD_TYPE = 16;

	private static final int CONSTANT_DYNAMIC = 17;

	private static final int CONSTANT_INVOKE_DYNAMIC = 18;

	private static final int CONSTANT_MODULE = 19;

	private static final int CONSTANT_PACKAGE = 20;

	private static final String RUNTIME_VISIBLE_ANNOTATIONS = "RuntimeVisibleAnnotations";

	private final Resource resource;

	/**
	 * 指定要读的资源（必须是 {@code .class} 文件）。
	 * @param resource 类文件资源
	 */
	public SimpleMetadataReader(Resource resource) {
		this.resource = resource;
	}

	@Override
	public ClassMetadata getClassMetadata() {
		try {
			byte[] bytes;
			try (InputStream stream = this.resource.getInputStream()) {
				bytes = stream.readAllBytes();
			}
			try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
				return read(in);
			}
		}
		catch (IOException ex) {
			throw new IllegalStateException("Failed to read class metadata from " + this.resource.getDescription(), ex);
		}
	}

	private ClassMetadata read(DataInputStream in) throws IOException {
		if (in.readInt() != 0xCAFEBABE) {
			throw new IllegalStateException(this.resource.getDescription() + " is not a class file");
		}
		in.readUnsignedShort(); // minor
		in.readUnsignedShort(); // major

		ConstantPool pool = readConstantPool(in);
		String[] utf8 = pool.utf8();
		in.readUnsignedShort(); // access_flags
		String className = className(utf8, pool.classRefs(), in.readUnsignedShort());
		in.readUnsignedShort(); // super_class
		skip(in, 2L * in.readUnsignedShort()); // interfaces

		skipMembers(in); // fields
		skipMembers(in); // methods

		Set<String> annotations = new LinkedHashSet<>();
		Map<String, Map<String, String>> attributes = new LinkedHashMap<>();
		int attributeCount = in.readUnsignedShort();
		for (int i = 0; i < attributeCount; i++) {
			String attributeName = utf8[in.readUnsignedShort()];
			long length = in.readInt() & 0xFFFFFFFFL;
			if (RUNTIME_VISIBLE_ANNOTATIONS.equals(attributeName)) {
				readAnnotations(in, utf8, annotations, attributes);
			}
			else {
				skip(in, length);
			}
		}
		return new SimpleClassMetadata(className, annotations, attributes);
	}

	private ConstantPool readConstantPool(DataInputStream in) throws IOException {
		int count = in.readUnsignedShort();
		String[] utf8 = new String[count];
		int[] classRefs = new int[count];
		for (int i = 1; i < count; i++) {
			int tag = in.readUnsignedByte();
			switch (tag) {
				case CONSTANT_UTF8 -> utf8[i] = in.readUTF();
				case CONSTANT_CLASS -> classRefs[i] = in.readUnsignedShort();
				case CONSTANT_INTEGER, CONSTANT_FLOAT, CONSTANT_FIELDREF, CONSTANT_METHODREF,
						CONSTANT_INTERFACE_METHODREF, CONSTANT_NAME_AND_TYPE, CONSTANT_DYNAMIC,
						CONSTANT_INVOKE_DYNAMIC ->
					skip(in, 4);
				case CONSTANT_LONG, CONSTANT_DOUBLE -> {
					skip(in, 8);
					i++; // 8 字节常量占两个槽位（JVMS §4.4.5）
				}
				case CONSTANT_STRING, CONSTANT_METHOD_TYPE, CONSTANT_MODULE, CONSTANT_PACKAGE -> skip(in, 2);
				case CONSTANT_METHOD_HANDLE -> skip(in, 3);
				default -> throw new IllegalStateException(
						"Unknown constant pool tag " + tag + " in " + this.resource.getDescription());
			}
		}
		return new ConstantPool(utf8, classRefs);
	}

	private void skipMembers(DataInputStream in) throws IOException {
		int count = in.readUnsignedShort();
		for (int i = 0; i < count; i++) {
			skip(in, 6); // access_flags + name_index + descriptor_index
			int attributeCount = in.readUnsignedShort();
			for (int j = 0; j < attributeCount; j++) {
				in.readUnsignedShort(); // attribute_name_index
				skip(in, in.readInt() & 0xFFFFFFFFL);
			}
		}
	}

	private void readAnnotations(DataInputStream in, String[] utf8, Set<String> annotations,
			Map<String, Map<String, String>> attributes) throws IOException {
		int annotationCount = in.readUnsignedShort();
		for (int i = 0; i < annotationCount; i++) {
			String type = typeName(utf8[in.readUnsignedShort()]);
			annotations.add(type);
			Map<String, String> values = new LinkedHashMap<>();
			int pairCount = in.readUnsignedShort();
			for (int j = 0; j < pairCount; j++) {
				String name = utf8[in.readUnsignedShort()];
				String value = readElementValue(in, utf8);
				if (value != null) {
					values.put(name, value);
				}
			}
			attributes.put(type, values);
		}
	}

	/**
	 * 读一个 element_value 并返回「可读的值」；本书只把字符串值带出来，其余类型读掉但返回 {@code null}。
	 * @param in 输入
	 * @param utf8 常量池里的 UTF8 常量
	 * @return 字符串值或 {@code null}
	 * @throws IOException IO 错误
	 */
	private String readElementValue(DataInputStream in, String[] utf8) throws IOException {
		int tag = in.readUnsignedByte();
		switch (tag) {
			case 'B', 'C', 'I', 'S', 'Z', 'D', 'F', 'J' -> {
				in.readUnsignedShort();
				return null;
			}
			case 's' -> {
				return utf8[in.readUnsignedShort()];
			}
			case 'e' -> {
				in.readUnsignedShort();
				return utf8[in.readUnsignedShort()];
			}
			case 'c' -> {
				in.readUnsignedShort();
				return null;
			}
			case '@' -> {
				in.readUnsignedShort(); // 嵌套注解的类型
				int pairCount = in.readUnsignedShort();
				for (int i = 0; i < pairCount; i++) {
					in.readUnsignedShort();
					readElementValue(in, utf8);
				}
				return null;
			}
			case '[' -> {
				return readArrayElementValues(in, utf8);
			}
			default -> throw new IllegalStateException("Unknown element_value tag " + (char) tag);
		}
	}

	/**
	 * 读一个数组型 element_value，只带出第一个元素——够 {@code @ComponentScan("pkg")} 这种单值用法了。
	 * @param in 输入
	 * @param utf8 常量池里的 UTF8 常量
	 * @return 第一个元素的字符串值，或 {@code null}
	 * @throws IOException IO 错误
	 */
	private String readArrayElementValues(DataInputStream in, String[] utf8) throws IOException {
		int valueCount = in.readUnsignedShort();
		String only = null;
		for (int i = 0; i < valueCount; i++) {
			String value = readElementValue(in, utf8);
			if (i == 0) {
				only = value;
			}
		}
		return only;
	}

	private String className(String[] utf8, int[] classRefs, int thisClassIndex) {
		// this_class 是一个 CONSTANT_Class 槽位，它的 name_index 才指向 UTF8 描述符（形如 org/foo/Bar）
		return utf8[classRefs[thisClassIndex]].replace('/', '.');
	}

	/**
	 * 常量池的两个平行数组：UTF8 字符串，以及「CONSTANT_Class 槽位 → 它的 name_index」。
	 *
	 * <p>
	 * <b>为什么这里是 {@code record}</b>（CODE-STANDARDS 的 K2-b）：它是本书自创的、只在本类内部使用的纯数据载体—— 不进任何公共
	 * API、不需要继承、也不靠「同一个实例」表达身份（两个字段相同的常量池就是同一个常量池）。 这四条同时成立时，{@code record} 比手写
	 * {@code final class} 更准确：它把「不可变 + 值相等」从约定变成 语言保证。反例是 {@code PropertyValue}——那个要与真实
	 * Spring 保持形状，所以不能用 {@code record}。
	 *
	 * @param utf8 UTF8 常量
	 * @param classRefs 类引用
	 */
	private record ConstantPool(String[] utf8, int[] classRefs) {
	}

	private String typeName(String descriptor) {
		String name = descriptor;
		if (name.startsWith("L") && name.endsWith(";")) {
			name = name.substring(1, name.length() - 1);
		}
		return name.replace('/', '.');
	}

	private void skip(DataInputStream in, long bytes) throws IOException {
		long remaining = bytes;
		while (remaining > 0) {
			long skipped = in.skip(remaining);
			if (skipped <= 0) {
				throw new IOException("Unexpected end of class file " + this.resource.getDescription());
			}
			remaining -= skipped;
		}
	}

}
