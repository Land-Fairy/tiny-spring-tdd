package org.springframework.aop.target;

import org.springframework.aop.TargetSource;

/**
 * 可以在运行中换掉目标的目标源。
 * <p>
 * 这里是本章最能说明问题的地方：换掉目标之后，<b>代理对象一个字节都没变</b>，但它下一次调用就落到了新目标上。 因为代理手里只有「怎么拿目标」，没有目标的状态。
 * <p>
 * 真实 Spring 同名类 128 行，额外做了 {@code equals}/{@code hashCode} 的「双向相等」语义，以便两个
 * {@code HotSwappableTargetSource} 互换目标后仍能被同一个代理识别。
 */
public class HotSwappableTargetSource implements TargetSource<Object> {

	private volatile Object target;

	/**
	 * 创建一个目标源。
	 * @param target 初始目标，不能为 {@code null}
	 */
	public HotSwappableTargetSource(Object target) {
		if (target == null) {
			throw new IllegalArgumentException("Target object must not be null");
		}
		this.target = target;
	}

	/**
	 * 换掉目标。
	 * @param newTarget 新目标，不能为 {@code null}
	 * @return 被换下来的旧目标
	 */
	public synchronized Object swap(Object newTarget) {
		if (newTarget == null) {
			throw new IllegalArgumentException("Target object must not be null");
		}
		Object old = this.target;
		this.target = newTarget;
		return old;
	}

	@Override
	public Class<?> getTargetClass() {
		return this.target.getClass();
	}

	@Override
	public Object getTarget() {
		return this.target;
	}

	@Override
	public void releaseTarget(Object target) {
		// 不需要归还
	}

}
