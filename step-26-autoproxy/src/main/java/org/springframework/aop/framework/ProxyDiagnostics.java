package org.springframework.aop.framework;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 把 JVM 生成的代理类字节码取到手上。
 * <p>
 * 代理类不在我们的源码里，也不在 classpath 上，它由 {@code java.lang.reflect.Proxy} 在运行期合成：
 * <ol>
 * <li>类名是 {@code com.sun.proxy.$Proxy0}、{@code $Proxy1}……（同一个接口组合只生成一次，之后复用）；</li>
 * <li>字节码由 {@code sun.misc} / {@code jdk.internal} 里的 {@code ProxyGenerator} 拼出来；</li>
 * <li>它被直接 {@code defineClass} 进一个新的类加载器，<b>不落盘</b>。</li>
 * </ol>
 * 所以「看代理类长什么样」这件事，公开 API 里没有直接入口。唯一的官方后门是一个系统属性：
 * {@code -Djdk.proxy.ProxyGenerator.saveGeneratedFiles=true}（JDK 9 起；JDK 8 是
 * {@code sun.misc.ProxyGenerator.saveGeneratedFiles}）。设上之后，JVM 会把生成过的每一个代理类按
 * <b>包名建目录</b>写到当前工作目录下。
 * <p>
 * 「包名」这件事在 JDK 9 之后变了，写代码时最容易踩：
 * <ul>
 * <li>JDK 8：代理类叫 {@code com.sun.proxy.$Proxy0}，落盘在
 * {@code <user.dir>/com/sun/proxy/}；</li>
 * <li>JDK 9+：所有接口都在未命名模块时，代理类被放进一个动态模块，包名是 {@code jdk.proxy1}、
 * {@code jdk.proxy2}……（按模块归属递增），落盘在 {@code <user.dir>/jdk/proxy1/}。</li>
 * </ul>
 * 所以路径不能写死，得从代理类自己的包名推出来 —— 见 {@link #expectedDumpFile(Class)}。
 * <p>
 * 本类只做两件事：告诉你文件在哪、把它复制到你指定的位置。真正生成字节码的是 JVM，我们连一个字节都没写 ——这正是本章想让人看清的分界线。
 *
 * @see JdkDynamicAopProxy
 */
public final class ProxyDiagnostics {

	/** 让 JVM 落盘代理类的系统属性名。 */
	public static final String SAVE_PROPERTY = "jdk.proxy.ProxyGenerator.saveGeneratedFiles";

	private ProxyDiagnostics() {
	}

	/**
	 * 当前 JVM 是否被配置成「把代理类落盘」。
	 * @return 属性为 {@code true} 时返回 {@code true}
	 */
	public static boolean isSavingEnabled() {
		return Boolean.getBoolean(SAVE_PROPERTY);
	}

	/**
	 * JVM 落盘的根目录，就是当前工作目录。
	 * @return 根目录路径
	 */
	public static Path dumpRoot() {
		return Path.of(System.getProperty("user.dir"));
	}

	/**
	 * 推算一个代理类的字节码「应该在」哪里 —— 包名换成目录层级，文件名是 {@code $ProxyN.class}。
	 * @param proxyClass 代理类
	 * @return 预期路径（不保证文件存在）
	 */
	public static Path expectedDumpFile(Class<?> proxyClass) {
		Path directory = dumpRoot();
		String packageName = proxyClass.getPackageName();
		if (!packageName.isEmpty()) {
			directory = directory.resolve(packageName.replace('.', File.separatorChar));
		}
		return directory.resolve(proxyClass.getSimpleName() + ".class");
	}

	/**
	 * 把一个已经生成的代理类的字节码复制到目标目录。
	 * @param proxyClass 代理类（它的名字必须是 {@code $ProxyN} 这种形状）
	 * @param targetDirectory 复制到哪个目录
	 * @return 复制出来的文件路径
	 * @throws IllegalStateException 代理类没有落盘时（多半是没设 {@link #SAVE_PROPERTY}）
	 */
	public static Path dump(Class<?> proxyClass, Path targetDirectory) {
		Path source = expectedDumpFile(proxyClass);
		if (!Files.isRegularFile(source)) {
			throw new IllegalStateException(
					"代理类字节码没有落盘：" + source + "。启动 JVM 时加上 -D" + SAVE_PROPERTY + "=true（本模块的 surefire 配置已经加了）。");
		}
		try {
			Files.createDirectories(targetDirectory);
			Path target = targetDirectory.resolve(source.getFileName());
			Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
			return target;
		}
		catch (IOException ex) {
			throw new UncheckedIOException("复制代理类字节码失败：" + source, ex);
		}
	}

	/**
	 * 「代理类是不是已经生成过了」的判断依据：JVM 对同一组接口只生成一次，第二次拿到的是同一个类。
	 * @param left 第一个代理对象
	 * @param right 第二个代理对象
	 * @return 两个代理对象是否来自同一个代理类
	 */
	public static boolean sameProxyClass(Object left, Object right) {
		return left.getClass() == right.getClass();
	}

}
