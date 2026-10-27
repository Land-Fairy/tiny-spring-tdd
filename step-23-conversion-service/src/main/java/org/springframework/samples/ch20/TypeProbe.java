package org.springframework.samples.ch20;

import org.springframework.beans.factory.annotation.Value;

/**
 * 四种非 String 目标类型，全部从字符串转换而来。
 */
public class TypeProbe {

	@Value("${app.port}")
	private int port;

	@Value("${app.flag}")
	private boolean flag;

	@Value("${app.mode}")
	private Mode mode;

	@Value("${app.service}")
	private Class<?> serviceClass;

	public int getPort() {
		return this.port;
	}

	public boolean isFlag() {
		return this.flag;
	}

	public Mode getMode() {
		return this.mode;
	}

	public Class<?> getServiceClass() {
		return this.serviceClass;
	}

}
