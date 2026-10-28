package org.springframework.beans.factory.xml;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.GenericBeanDefinition;

/**
 * 把 {@code <beans>} 树翻译成图纸。
 *
 * <p>
 * 只认识五个标签/属性组合：
 *
 * <p>
 * 识别的结构只有五种：根元素 {@code beans}、子元素 {@code bean}、子元素 {@code property}， 以及属性
 * {@code id}／{@code class}／{@code scope}／{@code lazy-init}、 属性
 * {@code name}／{@code value}／{@code ref}。
 *
 * <p>
 * 注意 {@code value} 与 {@code ref} 走的是**两个不同的分支**，落到图纸上是两种不同的对象 （{@link TypedStringValue} 与
 * {@link RuntimeBeanReference}）。这是本章的核心设计， 也是 §2.2 那个红测试要证明的东西：如果两者都存成字符串， 填充阶段就会把 bean
 * 名字当成字段值塞进去。
 *
 * <p>
 * 真实对应物：{@code org.springframework.beans.factory.xml.DefaultBeanDefinitionDocumentReader}（349
 * 行） + {@code BeanDefinitionParserDelegate}（1544 行）。真实实现把「遍历文档」与
 * 「解释单个标签」拆成两个类，后者大得多——因为它要处理命名空间、构造器参数、 内部 Bean、集合、p／c 命名空间、lookup-method 等等。
 *
 * @see org.springframework.beans.factory.xml.DefaultBeanDefinitionDocumentReader
 */
public class DefaultBeanDefinitionDocumentReader implements BeanDefinitionDocumentReader {

	public static final String BEANS_ELEMENT = "beans";

	public static final String BEAN_ELEMENT = "bean";

	public static final String PROPERTY_ELEMENT = "property";

	public static final String ID_ATTRIBUTE = "id";

	public static final String CLASS_ATTRIBUTE = "class";

	public static final String SCOPE_ATTRIBUTE = "scope";

	public static final String PRIMARY_ATTRIBUTE = "primary";

	public static final String LAZY_INIT_ATTRIBUTE = "lazy-init";

	public static final String NAME_ATTRIBUTE = "name";

	public static final String VALUE_ATTRIBUTE = "value";

	public static final String REF_ATTRIBUTE = "ref";

	private static final String TRUE = "true";

	@Override
	public int registerBeanDefinitions(Document document, BeanDefinitionRegistry registry) {
		Element root = document.getDocumentElement();
		if (!BEANS_ELEMENT.equals(root.getTagName())) {
			throw new BeanDefinitionStoreException("Root element must be <beans> but was <" + root.getTagName() + ">");
		}
		// 同一份文档里出现的名字不能重复——这是书写错误，不是覆盖。
		// 跨文档重复是另一回事（配置中心覆盖场景），由注册表按 allowBeanDefinitionOverriding 处理。
		Set<String> namesInThisDocument = new HashSet<>();
		int count = 0;
		for (Element element : childElements(root)) {
			count += registerBeanDefinition(element, registry, namesInThisDocument);
		}
		return count;
	}

	/**
	 * 三个「一个字面量就能表达」的属性：作用域、主候选、懒加载。
	 *
	 * <p>
	 * 抽出来不是为了少写几行，而是为了让 {@link #registerBeanDefinition} 保持在一屏之内——
	 * 那个方法负责的是「这个元素合不合法」，而这里负责的是「把属性搬到图纸上」， 两件事本来就不该挤在一个方法体里。
	 * @param element {@code <bean>} 元素
	 * @param definition 正在组装的图纸
	 */
	private void applyScopeAndFlags(Element element, GenericBeanDefinition definition) {
		String scope = element.getAttribute(SCOPE_ATTRIBUTE);
		if (!scope.isEmpty()) {
			definition.setScope(scope);
		}
		if (TRUE.equals(element.getAttribute(PRIMARY_ATTRIBUTE))) {
			// XML 走的是显式声明这条路：注解路径由扫描器翻译成同一个标记
			definition.setPrimary(true);
		}
		if (TRUE.equals(element.getAttribute(LAZY_INIT_ATTRIBUTE))) {
			definition.setLazyInit(true);
		}
	}

	private int registerBeanDefinition(Element element, BeanDefinitionRegistry registry,
			Set<String> namesInThisDocument) {
		if (!BEAN_ELEMENT.equals(element.getTagName())) {
			throw new BeanDefinitionStoreException("Unknown element <" + element.getTagName()
					+ "> in <beans>: this document reader only understands <bean>"
					+ " (nested <beans>, <import> and <alias> arrive in a later chapter)");
		}
		String id = element.getAttribute(ID_ATTRIBUTE);
		if (id.isEmpty()) {
			throw new BeanDefinitionStoreException("Bean id must not be empty (element <bean class=\""
					+ element.getAttribute(CLASS_ATTRIBUTE) + "\">)");
		}
		if (!namesInThisDocument.add(id)) {
			// 真实 Spring 的措辞：BeanDefinitionParserDelegate#checkNameUniqueness
			// "Bean name 'x' is already used in this <beans> element"
			throw new BeanDefinitionStoreException("Bean name '" + id + "' is already used in this <beans> element");
		}
		String className = element.getAttribute(CLASS_ATTRIBUTE);
		if (className.isEmpty()) {
			throw new BeanDefinitionStoreException("No bean class name specified on bean definition '" + id
					+ "': the class attribute is required in this book");
		}

		GenericBeanDefinition definition = new GenericBeanDefinition();
		definition.setBeanClassName(className);
		applyScopeAndFlags(element, definition);
		definition.setPropertyValues(parsePropertyElements(id, element));
		registry.registerBeanDefinition(id, definition);
		return 1;
	}

	private MutablePropertyValues parsePropertyElements(String beanName, Element beanElement) {
		MutablePropertyValues propertyValues = new MutablePropertyValues();
		for (Element element : childElements(beanElement)) {
			if (!PROPERTY_ELEMENT.equals(element.getTagName())) {
				throw new BeanDefinitionStoreException("Unknown element <" + element.getTagName()
						+ "> inside <bean id=\"" + beanName + "\">: only <property> is supported");
			}
			propertyValues.add(parseProperty(beanName, element));
		}
		return propertyValues;
	}

	private PropertyValue parseProperty(String beanName, Element element) {
		String name = element.getAttribute(NAME_ATTRIBUTE);
		if (name.isEmpty()) {
			throw new BeanDefinitionStoreException(
					"<property> inside <bean id=\"" + beanName + "\"> must have a name attribute");
		}
		boolean hasValue = element.hasAttribute(VALUE_ATTRIBUTE);
		boolean hasRef = element.hasAttribute(REF_ATTRIBUTE);
		if (hasValue == hasRef) {
			throw new BeanDefinitionStoreException("<property name=\"" + name + "\"> of bean '" + beanName
					+ "' must have exactly one of 'value' or 'ref'" + " (found " + (hasValue ? "both" : "neither")
					+ ")");
		}
		if (hasValue) {
			// 字面量：原文进图纸，类型转换留到填充阶段（第 23 章）
			return new PropertyValue(name, new TypedStringValue(element.getAttribute(VALUE_ATTRIBUTE)));
		}
		// 引用：只记名字。此刻那个 bean 可能还没被注册，更不可能有对象。
		return new PropertyValue(name, new RuntimeBeanReference(element.getAttribute(REF_ATTRIBUTE)));
	}

	private static List<Element> childElements(Element parent) {
		List<Element> elements = new ArrayList<>();
		NodeList children = parent.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node node = children.item(i);
			if (node instanceof Element element) {
				elements.add(element);
			}
		}
		return elements;
	}

}
