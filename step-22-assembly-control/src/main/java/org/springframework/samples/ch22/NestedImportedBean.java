package org.springframework.samples.ch22;

/**
 * 被 {@link ImportedConfiguration} 再导入一层的类。
 */
public class NestedImportedBean {

	/**
	 * 标记方法。
	 * @return 固定字符串
	 */
	public String hello() {
		return "nested";
	}

}
