package org.springframework.samples.ch13;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.beans.factory.DisposableBean;

/**
 * 记录「销毁时谁先谁后」的探针。
 *
 * <p>
 * 事件记在静态列表里，因为销毁发生时探针对象马上就要被丢掉了，测试拿不到它。
 */
public class DestructionOrderProbe implements DisposableBean {

	public static final List<String> DESTROYED = new CopyOnWriteArrayList<>();

	private String name = "unnamed";

	/**
	 * 由属性填充写入名字。
	 * @param name 记录事件时用的名字
	 */
	public void setName(String name) {
		this.name = name;
	}

	@Override
	public void destroy() {
		DESTROYED.add(this.name);
	}

	/**
	 * 清空记录。
	 */
	public static void reset() {
		DESTROYED.clear();
	}

}
