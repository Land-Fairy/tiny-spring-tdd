package org.springframework.samples.ch22;

/**
 * 创建时会记一笔的 Bean。
 */
public class TrackedBean {

	private final String name;

	/**
	 * 造一个，并记一次创建。
	 * @param name 名字
	 */
	public TrackedBean(String name) {
		this.name = name;
		CreationTracker.recordCreation();
	}

	/**
	 * 名字。
	 * @return 名字
	 */
	public String getName() {
		return this.name;
	}

}
