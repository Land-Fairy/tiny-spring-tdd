package org.springframework.core.type.classreading;

/**
 * 元数据读取器：从「一个类」得到 {@link ClassMetadata}。
 *
 * <p>
 * 两个实现的差别是本章的全部主题：
 * <ul>
 * <li>{@link SimpleMetadataReader}：读字节码，不加载类</li>
 * <li>{@link ReflectionMetadataReader}：用反射，加载并初始化类</li>
 * </ul>
 *
 * @see org.springframework.core.type.classreading.MetadataReader
 */
public interface MetadataReader {

	/**
	 * 读取元数据。
	 * @return 类元数据
	 */
	ClassMetadata getClassMetadata();

}
