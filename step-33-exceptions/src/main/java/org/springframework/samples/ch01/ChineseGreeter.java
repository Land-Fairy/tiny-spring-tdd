package org.springframework.samples.ch01;

/**
 * {@link Greeter} 的中文实现。
 *
 * <p>
 * 它和 {@link EnglishGreeter} 的存在是故意的：同一个接口有两个实现， 「按类型取 Bean」立刻变成一道没有唯一答案的选择题。
 */
public class ChineseGreeter implements Greeter {

	@Override
	public String greet(String name) {
		return "你好，" + name + "！";
	}

}
