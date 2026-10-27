package org.springframework.samples.ch06;

import java.util.Map;

import org.springframework.beans.factory.ListableBeanFactory;

/**
 * 对照组：一个<b>需要「知道有哪些 Bean」</b> 的调用方。
 *
 * <p>
 * 它必须依赖 {@link ListableBeanFactory}，因为「列出全部实现」这件事不在 {@code BeanFactory} 的承诺里。
 * 这个类存在的意义是让「分层」有代价可看：
 *
 * <table border="1">
 * <caption>同一个需求，选错层的后果</caption>
 * <tr>
 * <th>调用方</th>
 * <th>依赖的层</th>
 * <th>能力</th>
 * <th>代价</th>
 * </tr>
 * <tr>
 * <td>{@link GreetingRenderer}</td>
 * <td>{@code BeanFactory}</td>
 * <td>按名字取一个</td>
 * <td>无</td>
 * </tr>
 * <tr>
 * <td>{@code GreetingCatalog}</td>
 * <td>{@code ListableBeanFactory}</td>
 * <td>按名字取一个 + 枚举全部</td>
 * <td>任何实现 {@code ListableBeanFactory} 的类都必须能枚举——包括测试桩</td>
 * </tr>
 * </table>
 *
 * <p>
 * 换句话说：<b>接口拆层的收益是「调用方少承诺」，代价是「实现方多实现」</b>。把这两件事摆在一起， 才能判断某个方法应该放在哪一层。
 */
public class GreetingCatalog {

	private final ListableBeanFactory beanFactory;

	/**
	 * 创建一个目录。
	 * @param beanFactory 需要能枚举 Bean 的容器
	 */
	public GreetingCatalog(ListableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	/**
	 * 所有 Greeter 的名字。
	 * @return 名字数组
	 */
	public String[] greeterNames() {
		return this.beanFactory.getBeanNamesForType(Greeter.class);
	}

	/**
	 * 用每个 Greeter 各渲染一次。
	 * @param name 名字
	 * @return 名字 → 问候语
	 */
	public Map<String, String> renderAll(String name) {
		return this.beanFactory.getBeansOfType(Greeter.class)
			.entrySet()
			.stream()
			.collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, (entry) -> entry.getValue().greet(name)));
	}

}
