package org.springframework.core.io;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;

/**
 * 从任意 URL 读：{@code file:}、{@code http:}、{@code jar:}、{@code jrt:}……
 *
 * <p>
 * 它是四种来源里唯一**依赖外部世界**的一种：网络断了、服务端 500、证书过期，都会在这里变成 {@code IOException}。所以
 * {@link #exists()} 对 URL 资源注定是「尽力而为」。
 *
 * @see org.springframework.core.io.UrlResource 真实对应物
 */
public class UrlResource extends AbstractResource {

	private final URL url;

	public UrlResource(URI uri) throws IOException {
		this.url = uri.toURL();
	}

	public UrlResource(URL url) {
		this.url = url;
	}

	@Override
	public InputStream getInputStream() throws IOException {
		return this.url.openConnection().getInputStream();
	}

	/**
	 * 覆写：URL 的路径部分最后一段就是文件名。
	 */
	@Override
	public String getFilename() {
		return filenameOf(this.url.getPath());
	}

	@Override
	public String getDescription() {
		return "URL [" + this.url + "]";
	}

	public URL getUrl() {
		return this.url;
	}

}
