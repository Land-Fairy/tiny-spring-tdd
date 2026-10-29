package org.springframework.aop.target;

import org.springframework.aop.TargetSource;

/**
 * 最常见的目标源：目标就是这一根引用，取和还都是空操作。
 * <p>
 * 本类的存在本身就是本章的结论：<b>字段不在代理上，字段在目标里</b>。代理只持有一个「怎么拿到目标」的对象； 属性填充、初始化回调、{@code @Autowired}
 * 全都写在目标上。
 * <p>
 * 真实 Spring 同名类 92 行，大部分是 {@code equals}/{@code hashCode}/{@code toString} 与序列化支持。
 *
 * @see org.springframework.aop.target.SingletonTargetSource
 */
public class SingletonTargetSource implements TargetSource<Object> {

	private final Object target;

	/**
	 * 包住一个已经存在的目标。
	 * @param target 目标对象，不能为 {@code null}
	 */
	public SingletonTargetSource(Object target) {
		if (target == null) {
			throw new IllegalArgumentException("Target object must not be null");
		}
		this.target = target;
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
		// 单例目标不需要归还
	}

	@Override
	public String toString() {
		return "SingletonTargetSource(" + this.target + ")";
	}

}
