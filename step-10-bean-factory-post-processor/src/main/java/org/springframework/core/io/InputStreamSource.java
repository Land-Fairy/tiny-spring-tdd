package org.springframework.core.io;

import java.io.IOException;
import java.io.InputStream;

/**
 * 能给我一个输入流的东西。
 *
 * <p>
 * 这一层单独抽出来的唯一理由是：有些东西只承诺「能流出字节」，并不承诺能被多次打开、 能被量长度、能有文件名。真实 Spring 用这个区分让「一次性」的资源也能进同一套
 * API。
 *
 * @see org.springframework.core.io.InputStreamSource 真实对应物
 */
public interface InputStreamSource {

	/**
	 * 打开一个输入流。调用方负责关闭。
	 * @return 数据流
	 * @throws IOException 打不开
	 */
	InputStream getInputStream() throws IOException;

}
