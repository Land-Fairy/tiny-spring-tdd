package org.springframework.core.io;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * 内容就在内存里：一个 byte 数组。
 *
 * <p>
 * 它存在的意义不是「方便测试」，而是**证明抽象真的与来源无关**—— 一个根本不在磁盘上的东西，也能满足同一个接口。
 *
 * @see org.springframework.core.io.ByteArrayResource 真实对应物
 */
public class ByteArrayResource extends AbstractResource {

	private final byte[] bytes;

	public ByteArrayResource(byte[] bytes) {
		this.bytes = bytes.clone();
	}

	@Override
	public InputStream getInputStream() {
		return new ByteArrayInputStream(this.bytes);
	}

	@Override
	public boolean exists() {
		return true;
	}

	@Override
	public String getFilename() {
		return null;
	}

	@Override
	public String getDescription() {
		return "byte array resource [length=" + this.bytes.length + "]";
	}

}
