package org.springframework.aop.framework;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.aop.ClassFilter;
import org.springframework.aop.MethodInterceptor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.NameMatchMethodPointcut;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 25 章：Advisor = Pointcut + Advice。**只有匹配的方法才被增强**—— 这句话是整个 AOP 的对外承诺，也是本章最重要的测试。
 */
class AdvisorChainTests {

	public interface Library {

		String book(String isbn);

		int count();

	}

	public static class LibraryImpl implements Library {

		final List<String> events = new ArrayList<>();

		@Override
		public String book(String isbn) {
			this.events.add("book");
			return "booked " + isbn;
		}

		@Override
		public int count() {
			this.events.add("count");
			return 7;
		}

	}

	private static MethodInterceptor recording(List<String> calls) {
		return (invocation) -> {
			calls.add(invocation.getMethod().getName());
			return invocation.proceed();
		};
	}

	private static JdkDynamicAopProxy proxy(AdvisedSupport advised) {
		return new JdkDynamicAopProxy(advised);
	}

	@Test
	void onlyMatchingMethodsRunThroughTheChain() {
		List<String> calls = new ArrayList<>();
		LibraryImpl target = new LibraryImpl();
		AdvisedSupport advised = new AdvisedSupport(target);
		NameMatchMethodPointcut pointcut = new NameMatchMethodPointcut();
		pointcut.setMappedNames("book*");
		advised.addAdvisor(new DefaultPointcutAdvisor(pointcut, recording(calls)));

		Library library = (Library) proxy(advised).getProxy();
		library.book("isbn-1");
		library.count();

		assertThat(calls).containsExactly("book");
	}

	@Test
	void unmatchedMethodStillReachesTheTarget() {
		List<String> calls = new ArrayList<>();
		LibraryImpl target = new LibraryImpl();
		AdvisedSupport advised = new AdvisedSupport(target);
		NameMatchMethodPointcut pointcut = new NameMatchMethodPointcut();
		pointcut.setMappedNames("book*");
		advised.addAdvisor(new DefaultPointcutAdvisor(pointcut, recording(calls)));

		Library library = (Library) proxy(advised).getProxy();

		assertThat(library.count()).isEqualTo(7);
		assertThat(target.events).containsExactly("count");
	}

	@Test
	void classFilterCanTurnOffAnAdvisorForTheWholeClass() {
		List<String> calls = new ArrayList<>();
		AdvisedSupport advised = new AdvisedSupport(new LibraryImpl());
		NameMatchMethodPointcut pointcut = new NameMatchMethodPointcut();
		pointcut.setMappedNames("*");
		pointcut.setClassFilter((ClassFilter) (clazz) -> false);
		advised.addAdvisor(new DefaultPointcutAdvisor(pointcut, recording(calls)));

		Library library = (Library) proxy(advised).getProxy();
		library.book("isbn-2");

		assertThat(calls).isEmpty();
	}

	@Test
	void advisorsRunInTheOrderTheyWereAdded() {
		List<String> calls = new ArrayList<>();
		AdvisedSupport advised = new AdvisedSupport(new LibraryImpl());
		advised.addAdvisor(new DefaultPointcutAdvisor((MethodInterceptor) (invocation) -> {
			calls.add("first");
			return invocation.proceed();
		}));
		advised.addAdvisor(new DefaultPointcutAdvisor((MethodInterceptor) (invocation) -> {
			calls.add("second");
			return invocation.proceed();
		}));

		Library library = (Library) proxy(advised).getProxy();
		library.book("isbn-3");

		assertThat(calls).containsExactly("first", "second");
	}

	@Test
	void interceptorsCanRewriteTheArgumentsSeenByTheTarget() {
		AdvisedSupport advised = new AdvisedSupport(new Library() {
			@Override
			public String book(String isbn) {
				return "got " + isbn;
			}

			@Override
			public int count() {
				return 0;
			}
		});
		advised.addAdvisor(new DefaultPointcutAdvisor((MethodInterceptor) (invocation) -> {
			invocation.getArguments()[0] = "rewritten";
			return invocation.proceed();
		}));

		Library library = (Library) proxy(advised).getProxy();

		assertThat(library.book("original")).isEqualTo("got rewritten");
	}

	@Test
	void withoutAdvisorsTheProxyIsAPlainForwarder() {
		AdvisedSupport advised = new AdvisedSupport(new LibraryImpl());

		Library library = (Library) proxy(advised).getProxy();

		assertThat(library.book("isbn-4")).isEqualTo("booked isbn-4");
	}

}
