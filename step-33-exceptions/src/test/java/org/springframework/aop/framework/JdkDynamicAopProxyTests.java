package org.springframework.aop.framework;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.aop.MethodInterceptor;
import org.springframework.aop.MethodInvocation;
import org.springframework.aop.support.AopProxyUtils;
import org.springframework.aop.support.DefaultPointcutAdvisor;

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
	private static final class RecordingInterceptor implements MethodInterceptor {

		private final List<String> calls = new ArrayList<>();

		private final List<Object[]> arguments = new ArrayList<>();

		private Object result;

		RecordingInterceptor(Object result) {
			this.result = result;
		}

		@Override
		public Object invoke(MethodInvocation invocation) {
			Method method = invocation.getMethod();
			this.calls.add(method.getName());
			this.arguments.add(invocation.getArguments());
			// Object 上的方法有固定返回类型，不能拿一个 String 去冒充 int/boolean。
			return switch (method.getName()) {
				case "hashCode" -> 12345;
				case "equals" -> true;
				default -> this.result;
			};
		}

	}

	/** 一条「匹配所有方法」的 Advisor：第 25 章起，拦截器必须由 Advisor 承载。 */
	private static Object proxyWith(Object target, MethodInterceptor interceptor) {
		AdvisedSupport advised = new AdvisedSupport(target);
		advised.addAdvisor(new DefaultPointcutAdvisor(interceptor));
		return new JdkDynamicAopProxy(advised).getProxy();
	}

	@Test
	void interceptsEveryCallOnTheInterface() {
		RecordingInterceptor recorded = new RecordingInterceptor("intercepted");

		Greeter greeter = (Greeter) proxyWith(new GreeterImpl(), recorded);

		assertThat(greeter.greet("Ann")).isEqualTo("intercepted");
		assertThat(recorded.calls).containsExactly("greet");
		assertThat(recorded.arguments.get(0)).containsExactly("Ann");
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
	void toStringGoesThroughTheInterceptorButHashCodeDoesNot() {
		RecordingInterceptor recorded = new RecordingInterceptor("sentinel");

		Object proxy = proxyWith(new GreeterImpl(), recorded);

		assertThat(proxy.toString()).isEqualTo("sentinel");
		// 第 25 章补的契约：hashCode/equals 不走拦截器链，否则放进 HashMap 会炸。
		assertThat(proxy.hashCode()).isNotEqualTo(12345);
		assertThat(recorded.calls).containsExactly("toString");
	}

	@Test
	void getClassIsTheOneMethodThatCannotBeIntercepted() {
		RecordingInterceptor recorded = new RecordingInterceptor("sentinel");

		Object proxy = proxyWith(new GreeterImpl(), recorded);

		assertThat(proxy.getClass()).isNotEqualTo(GreeterImpl.class);
		assertThat(recorded.calls).isEmpty();
	}

	@Test
	void defaultMethodsAreInterceptedInsteadOfRunningTheirBody() {
		RecordingInterceptor recorded = new RecordingInterceptor("intercepted");

		WithDefault proxy = (WithDefault) proxyWith(new DefaultImpl(), recorded);

		assertThat(proxy.defaultGreeting()).isEqualTo("intercepted");
		assertThat(recorded.calls).containsExactly("defaultGreeting");
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
