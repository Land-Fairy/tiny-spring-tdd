package org.springframework.samples.ch09;

/**
 * 构造器一定会抛异常的样本 Bean。
 *
 * <p>
 * 放在测试源码里而不是主源码里，因为它只用于「启动失败之后容器怎么收拾」这一个场景， 不属于这本书要教给读者的实现。
 */
public class ExplodingBean {

	public ExplodingBean() {
		throw new IllegalStateException("boom");
	}

}
