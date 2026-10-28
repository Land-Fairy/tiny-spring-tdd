package org.springframework.aop.framework;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.aop.AfterReturningAdvice;
import org.springframework.aop.MethodBeforeAdvice;
import org.springframework.aop.MethodInterceptor;
import org.springframework.aop.MethodInvocation;
import org.springframework.aop.support.DefaultPointcutAdvisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 第 25 章：三种增强的区别只有一个字 —— {@code proceed()} 写在哪儿。
 */
class AdviceTests {

	public interface BookService {

		String book(String isbn);

	}

	public static class BookServiceImpl implements BookService {

		final List<String> events;

		BookServiceImpl(List<String> events) {
			this.events = events;
		}

		@Override
		public String book(String isbn) {
			this.events.add("target");
			return "booked " + isbn;
		}

	}

	@Test
	void beforeAdviceRunsBeforeTheTarget() {
		List<String> events = new ArrayList<>();
		AdvisedSupport advised = new AdvisedSupport(new BookServiceImpl(events));
		advised.addAdvisor(
				new DefaultPointcutAdvisor((MethodBeforeAdvice) (method, args, target) -> events.add("before")));

		BookService proxy = (BookService) new JdkDynamicAopProxy(advised).getProxy();

		assertThat(proxy.book("isbn-1")).isEqualTo("booked isbn-1");
		assertThat(events).containsExactly("before", "target");
	}

	@Test
	void afterReturningAdviceSeesTheReturnValue() {
		List<String> seen = new ArrayList<>();
		AdvisedSupport advised = new AdvisedSupport(new BookServiceImpl(new ArrayList<>()));
		advised.addAdvisor(new DefaultPointcutAdvisor(
				(AfterReturningAdvice) (returnValue, method, args, target) -> seen.add((String) returnValue)));

		BookService proxy = (BookService) new JdkDynamicAopProxy(advised).getProxy();

		assertThat(proxy.book("isbn-2")).isEqualTo("booked isbn-2");
		assertThat(seen).containsExactly("booked isbn-2");
	}

	@Test
	void aroundAdviceCanReplaceTheResult() {
		AdvisedSupport advised = new AdvisedSupport(new BookServiceImpl(new ArrayList<>()));
		advised.addAdvisor(
				new DefaultPointcutAdvisor((MethodInterceptor) invocation -> "wrapped: " + invocation.proceed()));

		BookService proxy = (BookService) new JdkDynamicAopProxy(advised).getProxy();

		assertThat(proxy.book("isbn-3")).isEqualTo("wrapped: booked isbn-3");
	}

	@Test
	void proceedPositionDecidesWhenTheAdviceRuns() {
		List<String> events = new ArrayList<>();
		AdvisedSupport advised = new AdvisedSupport(new BookServiceImpl(events));
		// 顺序：前置（先做事再往下）→ 环绕（进去前后各记一笔）→ 后置（先往下再做事）
		advised.addAdvisor(
				new DefaultPointcutAdvisor((MethodBeforeAdvice) (method, args, target) -> events.add("before")));
		advised.addAdvisor(new DefaultPointcutAdvisor((MethodInterceptor) invocation -> {
			events.add("around-before");
			Object result = invocation.proceed();
			events.add("around-after");
			return result;
		}));
		advised.addAdvisor(new DefaultPointcutAdvisor(
				(AfterReturningAdvice) (returnValue, method, args, target) -> events.add("after")));

		BookService proxy = (BookService) new JdkDynamicAopProxy(advised).getProxy();
		proxy.book("isbn-4");

		assertThat(events).containsExactly("before", "around-before", "target", "after", "around-after");
	}

	@Test
	void beforeAdviceCanAbortTheCallByThrowing() {
		List<String> events = new ArrayList<>();
		AdvisedSupport advised = new AdvisedSupport(new BookServiceImpl(events));
		advised.addAdvisor(new DefaultPointcutAdvisor((MethodBeforeAdvice) (method, args, target) -> {
			throw new IllegalStateException("vetoed");
		}));

		BookService proxy = (BookService) new JdkDynamicAopProxy(advised).getProxy();

		assertThatThrownBy(() -> proxy.book("isbn-5")).isInstanceOf(IllegalStateException.class).hasMessage("vetoed");
		assertThat(events).isEmpty();
	}

	@Test
	void afterReturningAdviceDoesNotRunWhenTheTargetThrows() {
		List<String> seen = new ArrayList<>();
		AdvisedSupport advised = new AdvisedSupport(new BookService() {
			@Override
			public String book(String isbn) {
				throw new IllegalStateException("boom");
			}
		});
		advised.addAdvisor(new DefaultPointcutAdvisor(
				(AfterReturningAdvice) (returnValue, method, args, target) -> seen.add("after")));

		BookService proxy = (BookService) new JdkDynamicAopProxy(advised).getProxy();

		assertThatThrownBy(() -> proxy.book("isbn-6")).hasMessage("boom");
		assertThat(seen).isEmpty();
	}

	@Test
	void adviceSeesArgumentsAndTheProxyAsTarget() {
		List<Object> recorded = new ArrayList<>();
		AdvisedSupport advised = new AdvisedSupport(new BookServiceImpl(new ArrayList<>()));
		advised.addAdvisor(
				new DefaultPointcutAdvisor((MethodBeforeAdvice) (Method method, Object[] args, Object target) -> {
					recorded.add(method.getName());
					recorded.add(args[0]);
					recorded.add(target);
				}));

		Object proxy = new JdkDynamicAopProxy(advised).getProxy();
		((BookService) proxy).book("isbn-7");

		assertThat(recorded).hasSize(3);
		assertThat(recorded.get(0)).isEqualTo("book");
		assertThat(recorded.get(1)).isEqualTo("isbn-7");
		assertThat(recorded.get(2)).isSameAs(proxy);
	}

	@Test
	void interceptorCanReadTheMethodItWraps() {
		List<String> names = new ArrayList<>();
		AdvisedSupport advised = new AdvisedSupport(new BookServiceImpl(new ArrayList<>()));
		advised.addAdvisor(new DefaultPointcutAdvisor((MethodInterceptor) (MethodInvocation invocation) -> {
			names.add(invocation.getMethod().getName() + "/" + invocation.getArguments().length);
			return invocation.proceed();
		}));

		BookService proxy = (BookService) new JdkDynamicAopProxy(advised).getProxy();
		proxy.book("isbn-8");

		assertThat(names).containsExactly("book/1");
	}

}
