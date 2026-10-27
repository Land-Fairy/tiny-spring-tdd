package org.springframework.beans.factory.xml;

import java.io.IOException;
import java.io.InputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.support.AbstractBeanDefinitionReader;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * 从 XML 读图纸。两个动作：把资源变成 DOM，把 DOM 变图纸。
 *
 * <p>
 * 真实对应物：{@code org.springframework.beans.factory.xml.XmlBeanDefinitionReader}（564 行）。
 * 真实版本在这个类里还做了四件事，本书都没有：
 *
 * <ul>
 * <li>{@code validationModeDetector}：嗅探 DTD 还是 XSD，据此决定要不要联网校验
 * <li>{@code entityResolver}：把 XSD 的 systemId 重定向到 jar 内的本地副本（否则解析
 * {@code spring-beans.xsd} 时每次都要联网）
 * <li>命名空间标签交给 {@code NamespaceHandler}（{@code <context:component-scan>} 就是从这里进来的）
 * <li>{@code resourcesCurrentlyBeingLoaded}（一个 ThreadLocal 集合）用来检测 {@code <import>} 的循环引用
 * </ul>
 */
public class XmlBeanDefinitionReader extends AbstractBeanDefinitionReader {

	private BeanDefinitionDocumentReader documentReader = new DefaultBeanDefinitionDocumentReader();

	public XmlBeanDefinitionReader(BeanDefinitionRegistry registry) {
		super(registry);
	}

	public XmlBeanDefinitionReader(BeanDefinitionRegistry registry, ResourceLoader resourceLoader) {
		super(registry, resourceLoader != null ? resourceLoader : new DefaultResourceLoader());
	}

	public void setDocumentReader(BeanDefinitionDocumentReader documentReader) {
		this.documentReader = documentReader;
	}

	@Override
	public int loadBeanDefinitions(Resource resource) {
		Document document = doLoadDocument(resource);
		int countBefore = getRegistry().getBeanDefinitionCount();
		this.documentReader.registerBeanDefinitions(document, getRegistry());
		// 用「注册表前后数量差」而不是「文档里 bean 元素个数」计数，
		// 这样即使某个 id 覆盖了已有图纸（跨文档场景），返回值也不会说谎。
		return getRegistry().getBeanDefinitionCount() - countBefore;
	}

	private Document doLoadDocument(Resource resource) {
		try (InputStream inputStream = resource.getInputStream()) {
			InputSource inputSource = new InputSource(inputStream);
			inputSource.setSystemId(resource.getDescription());
			DocumentBuilder builder = newDocumentBuilderFactory().newDocumentBuilder();
			// 不设 ErrorHandler 时，默认实现会把 "Content is not allowed in prolog" 之类
			// 直接打到 stderr 上——测试日志里会凭空多出一堆红字，且内容不受我们控制。
			// 自己接管之后：warning 忽略、error/fatalError 一律抛出，交给外层统一加壳。
			builder.setErrorHandler(new ThrowingErrorHandler());
			return builder.parse(inputSource);
		}
		catch (IOException ex) {
			throw new BeanDefinitionStoreException("IOException parsing XML document from " + resource.getDescription(),
					ex);
		}
		catch (SAXException | ParserConfigurationException ex) {
			// 这条消息是「XML 坏了」与「配置写错了」的分界线：
			// SAX 的原始消息只说「第几行不行」，不说是哪个文件——所以这里必须补上资源描述。
			throw new BeanDefinitionStoreException(
					"Failed to parse XML document from " + resource.getDescription() + ": " + ex.getMessage(), ex);
		}
	}

	/**
	 * 把 SAX 的错误转成异常，避免解析器直接往 stderr 打印。
	 */
	private static final class ThrowingErrorHandler implements ErrorHandler {

		@Override
		public void warning(SAXParseException exception) {
			// 警告不影响文档语义，忽略（真实 Spring 交给 ProblemReporter 记日志）。
		}

		@Override
		public void error(SAXParseException exception) throws SAXException {
			throw exception;
		}

		@Override
		public void fatalError(SAXParseException exception) throws SAXException {
			throw exception;
		}

	}

	private static DocumentBuilderFactory newDocumentBuilderFactory() throws ParserConfigurationException {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		// 安全默认值：禁用 DOCTYPE 与外部实体。
		// 不加这两行的解析器是 XXE（XML External Entity）漏洞的经典形态：
		// 一份带 <!DOCTYPE ... SYSTEM "file:///etc/passwd"> 的 XML 能把本机文件读进错误消息。
		// 真实 Spring 不能这么干——它的 XML 头里必须能声明 DTD，所以改用 entityResolver 白名单。
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
		factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
		factory.setExpandEntityReferences(false);
		return factory;
	}

}
