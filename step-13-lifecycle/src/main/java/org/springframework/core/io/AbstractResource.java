package org.springframework.core.io;

import java.io.InputStream;

/**
 * 把「存在」这件可以用通用办法答出来的事实现好，子类只需要回答「数据从哪来」与「怎么称呼自己」。
 *
 * @see org.springframework.core.io.AbstractResource 真实对应物
 */
public abstract class AbstractResource implements Resource {

	/**
	 * 通用判断：能打开流就说明存在。注意这里**吞掉异常**——「不存在」是一个正常答案，不是故障。
	 *
	 * <p>
	 * 真实 Spring 会先尝试 {@code getFile().exists()}（文件系统更快、不占流），失败再退回开流。
	 * 本书只保留开流这一条路，代价是判断存在也要建立一次连接。
	 */
	@Override
	public boolean exists() {
		try (InputStream in = getInputStream()) {
			return in != null;
		}
		catch (Exception ex) {
			return false;
		}
	}

	/**
	 * 从「路径」里取最后一段。子类各自的 {@code getFilename()} 都调它，避免三份重复逻辑。
	 * @param path 形如 {@code ch07/app.properties} 或 {@code /tmp/x/app.properties}
	 * @return 最后一段，{@code null} 或空串时返回 {@code null}
	 */
	protected static String filenameOf(String path) {
		if (path == null || path.isEmpty()) {
			return null;
		}
		int slash = path.lastIndexOf('/');
		String filename = (slash >= 0) ? path.substring(slash + 1) : path;
		return filename.isEmpty() ? null : filename;
	}

	@Override
	public String toString() {
		return getDescription();
	}

}
