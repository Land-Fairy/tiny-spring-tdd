package org.springframework.samples.ch08;

/**
 * 打招呼。本章有两个实现，XML 里靠 {@code class} 属性选择用哪一个。
 */
public interface Greeter {

	String greet(String name);

}
