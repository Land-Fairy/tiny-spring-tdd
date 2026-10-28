package org.springframework.samples.ch05;

/**
 * 最底层的依赖：没有构造器参数，容器用无参构造器就能造出来（第 04 章）。
 */
public class GreetingRepository {

	/**
	 * 把名字包装成「名字本身」——真实项目里这里会是一次查询。
	 * @param name 被问候的人
	 * @return 展示用的名字
	 */
	public String nameOf(String name) {
		return name;
	}

}
