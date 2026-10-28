package org.springframework.samples.ch27;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * 一个靠字段注入拿协作对象的 Bean。
 * <p>
 * 它实现了一个接口，所以可以被 JDK 代理罩住 —— 而字段注入写的是<b>类</b>上的字段，代理类上没有这些字段。
 */
public class ServiceUser implements Describable {

	@Autowired
	private Library library;

	@Override
	public String describe() {
		return "用户看到：" + this.library.describe();
	}

	public Library getLibrary() {
		return this.library;
	}

}
