package org.springframework.beans;

/**
 * 一条<b>属性指令</b>：把 {@code value} 填进 bean 上名为 {@code name} 的属性。
 *
 * <p>
 * <b>为什么需要它</b>：容器要能「先记下配置、后执行配置」。如果图纸里直接存 {@code Object} 实例， 那所有 Bean
 * 都必须在解析配置的那一刻就被创建——这也是第 01 章那个 {@code Map} 容器做不到的地方。 {@code PropertyValue}
 * 是同一件事的另一半：<b>把「要填什么」也当成数据存起来</b>。
 *
 * <p>
 * <b>为什么它不是 {@code record}</b>：这里最容易「顺手现代化」一下，但三条理由都不允许。
 * ① 真实 Spring 里它就是 {@code class}（208 行），换成 {@code record} 会破坏全书最大的卖点——逐行对照；
 * ② {@code record} 的 {@code equals}/{@code hashCode} 是<b>结构化相等</b>（只看字段值），而属性合并在意的是
 * 「谁覆盖了谁」这层身份语义：两条字段相同但来源不同的指令被判定为「相等」，合并结果会静默改变；
 * ③ {@code record} 不可继承，而真实 Spring 的 {@code PropertyValue} 体系里存在子类用法。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.beans.PropertyValue}（208 行）。它只有
 * {@code name} 和 {@code value} 两个字段，<b>没有 {@code type}</b>——因为目标类型可以从 setter 的签名
 * 推出来（{@code PropertyDescriptor#getPropertyType()}）。本书多留了一个可选的 {@code type}， 只用于消息：当属性连
 * setter 都找不到时，还能说出「本来想填什么类型」。
 *
 * @see org.springframework.beans.PropertyValue
 */
public class PropertyValue {

	private final String name;

	private final Object value;

	private final Class<?> type;

	/**
	 * 只知道名字和值。
	 * @param name 属性名（对应 setter 去掉 {@code set} 前缀后的驼峰名）
	 * @param value 要填入的值，允许为 {@code null}
	 */
	public PropertyValue(String name, Object value) {
		this(name, value, null);
	}

	/**
	 * 额外声明目标类型，仅用于错误消息。
	 * @param name 属性名
	 * @param value 要填入的值
	 * @param type 期望的目标类型，可为 {@code null}
	 */
	public PropertyValue(String name, Object value, Class<?> type) {
		if (name == null) {
			// 与真实 Spring 的 Assert.notNull(name, "Name must not be null") 对齐。
			// 没有这一行，null 名字会一路活到 BeanWrapper，最后变成
			// "Invalid property 'null' ..." —— 报错点离犯错点太远。
			throw new IllegalArgumentException("Name must not be null");
		}
		this.name = name;
		this.value = value;
		this.type = type;
	}

	/**
	 * 属性名。
	 * @return 属性名
	 */
	public String getName() {
		return this.name;
	}

	/**
	 * 要填入的值。
	 * @return 值，可能为 {@code null}
	 */
	public Object getValue() {
		return this.value;
	}

	/**
	 * 可选的目标类型声明。
	 * @return 声明的类型，未声明时为 {@code null}
	 */
	public Class<?> getType() {
		return this.type;
	}

	@Override
	public String toString() {
		return "PropertyValue{name='" + this.name + "', value=" + this.value + "}";
	}

}
