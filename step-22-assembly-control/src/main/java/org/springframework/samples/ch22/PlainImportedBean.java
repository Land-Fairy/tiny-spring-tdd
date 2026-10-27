package org.springframework.samples.ch22;

/**
 * 一个什么都没有的类——被 {@code @Import} 进来之后，它就是一个普通 Bean。
 */
public class PlainImportedBean {

	/**
	 * 随便返回点什么，证明容器真的 new 了它。
	 * @return 固定字符串
	 */
	public String hello() {
		return "plain";
	}

}
