package org.springframework.samples.ch16;

/**
 * 一个「往招呼语后面加标记」的装饰器。两层装饰叠起来时，<b>顺序就变得可观察了</b>。
 */
public class DecoratingGreeter implements Greeter {

	private final Greeter delegate;

	private final String mark;

	/**
	 * 用一个标记包住另一个打招呼者。
	 * @param delegate 被装饰者
	 * @param mark 标记文本
	 */
	public DecoratingGreeter(Greeter delegate, String mark) {
		this.delegate = delegate;
		this.mark = mark;
	}

	@Override
	public String greet() {
		return this.delegate.greet() + " [" + this.mark + "]";
	}

}
