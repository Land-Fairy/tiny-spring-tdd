package org.springframework.samples.ch11;

/**
 * {@link Audience} 的唯一实现：名字固定为 {@code console}。
 *
 * @see org.springframework.samples.ch11.Audience
 */
public class ConsoleAudience implements Audience {

	@Override
	public String name() {
		return "console";
	}

}
