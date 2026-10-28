package org.springframework.samples.ch19;

/**
 * 一个<b>没有</b>任何消歧标记的接口：两个实现、谁也不是默认、谁也没有顺序。
 *
 * <p>
 * 用它演示「参数名/字段名兜底」与「彻底选不出来时报错」。
 */
public interface Repository {

	/**
	 * 仓库名。
	 * @return 名字
	 */
	String name();

}
