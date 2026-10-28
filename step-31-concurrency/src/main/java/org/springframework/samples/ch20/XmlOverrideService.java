package org.springframework.samples.ch20;

import org.springframework.beans.factory.annotation.Value;

/**
 * 同一个属性既有注解值又有 XML 值：谁赢？
 */
public class XmlOverrideService {

	@Value("${app.greeting}")
	private String greeting;

	public String getGreeting() {
		return this.greeting;
	}

	public void setGreeting(String greeting) {
		this.greeting = greeting;
	}

}
