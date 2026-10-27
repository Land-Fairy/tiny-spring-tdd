package org.springframework.samples.ch14;

/**
 * 一个普通的协作对象——它自己不知道「工厂」的存在。
 */
public interface Greeter {

	/**
	 * 打招呼。
	 * @param name 对方名字
	 * @return 招呼语
	 */
	String greet(String name);

}
