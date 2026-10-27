package org.springframework.aop.framework;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.aop.support.AopProxyUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 第 24 章：JDK 动态代理到底拦住了什么、漏掉了什么。
 * <p>
 * 样本类全部是 {@code public static} —— 上一章踩过：反射只认 public 类型。
 */
class JdkDynamicAopProxyTests {

	interface Greeter {

		String greet(String name);

	}

	interface Named {

		String name();

	}

	interface WithDefault {

		default String defaultGreeting() {
			return "from default method";
		}

		String custom();

	}

	public static class GreeterImpl implements Greeter {

		@Override
		public String greet(String name) {
			return "hello " + name;
		}

	}

	public static class SubGreeter extends GreeterImpl implements Named {

		@Override
		public String name() {
			return "sub";
		}

	}

	public static class DefaultImpl implements WithDefault {

		@Override
		public String custom() {
			return "from custom";
		}

	}

	public static class FailingImpl implements Greeter {

		@Override
		public String greet(String name) {
			throw new IllegalStateException("boom");
		}

	}

	/** 记下每次调用的方法名与参数，返回值由测试指定。 */
	private static final class RecordingHandler implements InvocationHandler {

		private final List<String> calls = new ArrayList<>();

		private final List<Object[]> arguments = new ArrayList<>();

		private Object result;

		RecordingHandler(Object result) {
			this.result = result;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			this.calls.add(method.getName());
			this.arguments.add(args);
			// Object 上的方法有固定返回类型，不能拿一个 String 去冒充 int/boolean。
			return switch (method.getName()) {
				case "hashCode" -> 12345;
				case "equals" -> true;
				default -> this.result;
			};
		}

	}

	@Test
	void interceptsEveryCallOnTheInterface() {
		RecordingHandler handler = new RecordingHandler("intercepted");

		Greeter greeter = (Greeter) new JdkDynamicAopProxy(new GreeterImpl(), handler).getProxy();

		assertThat(greeter.greet("Ann")).isEqualTo("intercepted");
		assertThat(handler.calls).containsExactly("greet");
		assertThat(handler.arguments.get(0)).containsExactly("Ann");
	}

	@Test
	void forwardsToTheTargetWhenThereIsNoInterceptor() {
		Greeter greeter = (Greeter) new JdkDynamicAopProxy(new GreeterImpl()).getProxy();

		assertThat(greeter.greet("Ann")).isEqualTo("hello Ann");
	}

	@Test
	void proxyImplementsEveryInterfaceIncludingInheritedOnes() {
		Object proxy = new JdkDynamicAopProxy(new SubGreeter()).getProxy();

		assertThat(proxy).isInstanceOf(Greeter.class).isInstanceOf(Named.class);
		assertThat(((Greeter) proxy).greet("Ann")).isEqualTo("hello Ann");
		assertThat(((Named) proxy).name()).isEqualTo("sub");
	}

	@Test
	void objectMethodsReachTheHandlerToo() {
		RecordingHandler handler = new RecordingHandler("sentinel");

		Object proxy = new JdkDynamicAopProxy(new GreeterImpl(), handler).getProxy();

		assertThat(proxy.toString()).isEqualTo("sentinel");
		assertThat(proxy.hashCode()).isEqualTo(12345);
		assertThat(handler.calls).containsExactly("toString", "hashCode");
	}

	@Test
	void getClassIsTheOneMethodThatCannotBeIntercepted() {
		RecordingHandler handler = new RecordingHandler("sentinel");

		Object proxy = new JdkDynamicAopProxy(new GreeterImpl(), handler).getProxy();

		assertThat(proxy.getClass()).isNotEqualTo(GreeterImpl.class);
		assertThat(handler.calls).isEmpty();
	}

	@Test
	void defaultMethodsAreInterceptedInsteadOfRunningTheirBody() {
		RecordingHandler handler = new RecordingHandler("intercepted");

		WithDefault proxy = (WithDefault) new JdkDynamicAopProxy(new DefaultImpl(), handler).getProxy();

		assertThat(proxy.defaultGreeting()).isEqualTo("intercepted");
		assertThat(handler.calls).containsExactly("defaultGreeting");
	}

	@Test
	void cannotProxyAnObjectWithoutInterfaces() {
		JdkDynamicAopProxy aopProxy = new JdkDynamicAopProxy(new Object());

		assertThatIllegalArgumentException().isThrownBy(aopProxy::getProxy);
	}

	@Test
	void unwrapsTheExceptionThrownByTheTarget() {
		Greeter greeter = (Greeter) new JdkDynamicAopProxy(new FailingImpl()).getProxy();

		assertThatThrownBy(() -> greeter.greet("Ann")).isInstanceOf(IllegalStateException.class).hasMessage("boom");
	}

	@Test
	void collectsInterfacesFromSuperclassesAndSuperInterfaces() {
		Class<?>[] interfaces = AopProxyUtils.allInterfaces(SubGreeter.class);

		assertThat(Arrays.asList(interfaces)).contains(Greeter.class, Named.class);
		assertThat(interfaces).doesNotHaveDuplicates();
	}

	@Test
	void collectsSuperInterfacesOfTheImplementedOnes() {
		Class<?>[] interfaces = AopProxyUtils.allInterfaces(ArrayList.class);

		assertThat(Arrays.asList(interfaces)).contains(List.class, Iterable.class, java.util.Collection.class,
				java.util.RandomAccess.class);
		assertThat(interfaces).doesNotHaveDuplicates();
	}

}
