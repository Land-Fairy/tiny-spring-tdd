package org.springframework.samples.ch26;

/**
 * 本章样本：只有 {@code book*} 需要被增强。
 */
public interface Library {

	void book(String seat);

	void renew(String seat);

	int count();

}
