package org.springframework.aop.framework;

import org.springframework.aop.TargetSource;
import org.springframework.aop.target.HotSwappableTargetSource;
import org.springframework.aop.target.SingletonTargetSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.samples.ch27.Library;
import org.springframework.samples.ch27.LibraryImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * 第 27 章：目标不是一根字段，而是「取 → 用 → 还」的协议。
 *
 * @see org.springframework.aop.TargetSource
 * @see org.springframework.aop.target.HotSwappableTargetSource
 */
class TargetSourceTests {

	@Test
	@DisplayName("每次调用都取一次目标、还一次目标")
	void theTargetIsObtainedAndReleasedOncePerCall() {
		CountingTargetSource targetSource = new CountingTargetSource(new LibraryImpl());
		Library library = (Library) proxyFor(targetSource);

		library.getTitle();
		library.getTitle();

		assertThat(targetSource.gets).isEqualTo(2);
		assertThat(targetSource.releases).isEqualTo(2);
	}

	@Test
	@DisplayName("调用抛异常，目标也必须还回去 —— 否则池化的目标会漏")
	void theTargetIsReleasedEvenWhenTheCallThrows() {
		CountingTargetSource targetSource = new CountingTargetSource(new ExplodingLibrary());
		Library library = (Library) proxyFor(targetSource);

		assertThatIllegalStateException().isThrownBy(library::describe).withMessage("库里着火了");

		assertThat(targetSource.gets).isEqualTo(1);
		assertThat(targetSource.releases).isEqualTo(1);
	}

	@Test
	@DisplayName("换掉目标，代理一个字节没变，但它立刻落到新目标上")
	void hotSwappableTargetChangesWhereTheProxyGoes() {
		LibraryImpl first = new LibraryImpl();
		first.setTitle("老馆");
		HotSwappableTargetSource targetSource = new HotSwappableTargetSource(first);
		Library library = (Library) proxyFor(targetSource);

		assertThat(library.getTitle()).isEqualTo("老馆");

		LibraryImpl second = new LibraryImpl();
		second.setTitle("新馆");
		Object old = targetSource.swap(second);

		assertThat(library.getTitle()).isEqualTo("新馆");
		assertThat(old).isSameAs(first);
		// 状态留在老目标里，没被搬走，也没被复制
		assertThat(first.getTitle()).isEqualTo("老馆");
	}

	@Test
	@DisplayName("单例目标源：取回同一根引用，归还什么也不做")
	void singletonTargetSourceReturnsTheSameInstance() {
		LibraryImpl target = new LibraryImpl();
		SingletonTargetSource targetSource = new SingletonTargetSource(target);

		assertThat(targetSource.getTargetClass()).isEqualTo(LibraryImpl.class);
		assertThat(targetSource.getTarget()).isSameAs(target);
		assertThat(targetSource.getTarget()).isSameAs(targetSource.getTarget());
	}

	private Object proxyFor(TargetSource<?> targetSource) {
		ProxyFactory factory = new ProxyFactory();
		factory.setTargetSource(targetSource);
		return factory.getProxy();
	}

	/** 记录「取了几次、还了几次」的目标源。 */
	static class CountingTargetSource implements TargetSource<Library> {

		final Library target;

		int gets;

		int releases;

		CountingTargetSource(Library target) {
			this.target = target;
		}

		@Override
		public Class<?> getTargetClass() {
			return this.target.getClass();
		}

		@Override
		public Library getTarget() {
			this.gets++;
			return this.target;
		}

		@Override
		public void releaseTarget(Object released) {
			this.releases++;
		}

	}

	/** 调用即爆炸的图书馆。 */
	static class ExplodingLibrary implements Library {

		@Override
		public String getTitle() {
			return "爆炸馆";
		}

		@Override
		public void setTitle(String title) {
			// 不需要
		}

		@Override
		public String describe() {
			throw new IllegalStateException("库里着火了");
		}

	}

}
