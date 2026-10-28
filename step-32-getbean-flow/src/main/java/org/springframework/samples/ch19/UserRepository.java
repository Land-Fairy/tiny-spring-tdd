package org.springframework.samples.ch19;

/**
 * 名为 {@code userRepository} 的候选。
 */
public class UserRepository implements Repository {

	@Override
	public String name() {
		return "user";
	}

}
