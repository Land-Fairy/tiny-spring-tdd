package org.springframework.aop.framework;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 第 24 章：把 JVM 生成的 {@code $ProxyN} 字节码取到手上。
 */
class ProxyDiagnosticsTests {

	interface Pilot {

		String fly(String destination);

	}

	public static class PilotImpl implements Pilot {

		@Override
		public String fly(String destination) {
			return "flying to " + destination;
		}

	}

	@Test
	void savingIsEnabledByTheBuild() {
		assumeTrue(ProxyDiagnostics.isSavingEnabled(), "需要 -D" + ProxyDiagnostics.SAVE_PROPERTY + "=true");
		// 断言只是为了让这个前提可见：surefire 配置里已经打开，关掉它时本测试会被跳过而不是失败。
		assertThat(ProxyDiagnostics.dumpRoot()).isDirectory();
	}

	@Test
	void proxyClassIsWrittenToDiskAsAByteArrayWeCanRead() throws Exception {
		assumeTrue(ProxyDiagnostics.isSavingEnabled(), "需要 -D" + ProxyDiagnostics.SAVE_PROPERTY + "=true");

		Object proxy = new JdkDynamicAopProxy(new PilotImpl()).getProxy();

		Path dumped = ProxyDiagnostics.dump(proxy.getClass(), Path.of("target", "proxy-dump", "kept"));

		assertThat(proxy.getClass().getSimpleName()).startsWith("$Proxy");
		byte[] bytes = Files.readAllBytes(dumped);
		String constantPool = new String(bytes, StandardCharsets.ISO_8859_1);
		assertThat(constantPool).contains("java/lang/reflect/Proxy").contains("fly");
		assertThat(dumped.getFileName()).hasToString(proxy.getClass().getSimpleName() + ".class");
		// JDK 9+ 把代理类放进 jdk.proxyN 这个动态模块包；JDK 8 是 com.sun.proxy。
		assertThat(proxy.getClass().getPackageName()).isNotEmpty();
	}

	@Test
	void theSameInterfaceCombinationReusesOneProxyClass() {
		JdkDynamicAopProxy first = new JdkDynamicAopProxy(new PilotImpl());
		JdkDynamicAopProxy second = new JdkDynamicAopProxy(new PilotImpl());

		assertThat(ProxyDiagnostics.sameProxyClass(first.getProxy(), second.getProxy())).isTrue();
	}

}
