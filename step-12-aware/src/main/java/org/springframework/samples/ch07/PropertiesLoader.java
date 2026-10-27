package org.springframework.samples.ch07;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.io.Resource;

/**
 * 读 {@code key=value} 配置。它对来源一无所知——这正是 {@link Resource} 想要的。
 */
public class PropertiesLoader {

	/**
	 * 从任意资源读配置。
	 * @param resource 资源
	 * @return 有序的键值对
	 */
	public Map<String, String> load(Resource resource) {
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
			StringBuilder text = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				text.append(line).append('\n');
			}
			return parse(text.toString());
		}
		catch (IOException ex) {
			throw new IllegalStateException("Failed to read " + resource.getDescription(), ex);
		}
	}

	/**
	 * 解析文本。空行与 {@code #} 开头的行跳过，值里的空格原样保留。
	 * @param text 配置文本
	 * @return 有序的键值对
	 */
	public Map<String, String> parse(String text) {
		Map<String, String> result = new LinkedHashMap<>();
		for (String line : text.split("\n")) {
			String trimmed = line.trim();
			if (trimmed.isEmpty() || trimmed.startsWith("#")) {
				continue;
			}
			int equals = line.indexOf('=');
			if (equals < 0) {
				throw new IllegalArgumentException("Malformed line (no '='): " + line);
			}
			result.put(line.substring(0, equals).trim(), line.substring(equals + 1));
		}
		return result;
	}

}
