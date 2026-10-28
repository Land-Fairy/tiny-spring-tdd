package org.springframework.samples.ch27;

/**
 * 接口的实现，字段住在这里 —— 而不是住在代理上。
 */
public class LibraryImpl implements Library {

	private String title = "未命名图书馆";

	private int floor;

	@Override
	public String getTitle() {
		return this.title;
	}

	@Override
	public void setTitle(String title) {
		this.title = title;
	}

	@Override
	public String describe() {
		return this.title + "（" + this.floor + " 层）";
	}

	/** 接口里没有的属性：代理对象上不存在这个 setter。 */
	public void setFloor(int floor) {
		this.floor = floor;
	}

	public int getFloor() {
		return this.floor;
	}

}
