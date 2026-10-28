package org.springframework;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 第六道闸门：分层。
 *
 * <p>
 * <b>为什么把架构写成测试</b>：文档里的「分层」只能约束愿意读文档的人，而一条 {@code ArchRule} 会在每次 {@code mvn verify}
 * 里重算一遍。规矩一旦能被机器执行，它就不再是约定，而是事实。
 *
 * <p>
 * <b>两条规矩，两个方向</b>：
 * <ul>
 * <li>{@code beans} 不能依赖 {@code context}——依赖只能从外往里。这条规矩在实践中非常容易被破坏： 只要有一个处理器顺手读了一下
 * {@code @Primary}、{@code @Qualifier} 这样的<b>注解类</b>，方向就反了。
 * 正确做法是把注解翻译成图纸上的布尔量（{@code BeanDefinition#isPrimary()}），让内层只读事实。</li>
 * <li>{@code aop} 不能反向依赖 {@code beans} 的<b>实现</b>（{@code beans.factory.support}）。 它可以依赖
 * {@code BeanFactory} 这样的接口，因为代理需要问容器「这个 Bean 现在是什么状态」， 但不能依赖
 * {@code DefaultListableBeanFactory} 这种具体实现——否则增强逻辑就被钉死在某一种容器上。</li>
 * </ul>
 *
 * <p>
 * <b>真实 Spring 对应</b>：真实仓库同样用 ArchUnit 守住包依赖（{@code src/test/java/...archunit}），
 * 它的规约多达几十条，本文只留最能体现「依赖方向」的两条。
 */
@AnalyzeClasses(packages = "org.springframework", importOptions = ImportOption.DoNotIncludeTests.class)
class LayeringTests {

	/**
	 * {@code beans} 不得依赖 {@code context}。
	 * <p>
	 * 常量名用大写：仓库的 {@code ConstantName} 闸门比 ArchUnit 文档里的示例命名优先。
	 */
	@ArchTest
	static final ArchRule BEANS_MUST_NOT_DEPEND_ON_CONTEXT = noClasses().that()
		.resideInAPackage("org.springframework.beans..")
		.should()
		.dependOnClassesThat()
		.resideInAPackage("org.springframework.context..")
		// 早期章节还没有 context 包：允许「没类可查」而不是报错
		.allowEmptyShould(true)
		.because("依赖方向只能从 context 指向 beans；注解类属于外层，内层不许回头读它");

	/**
	 * {@code aop} 不得反向依赖 {@code beans} 的实现类。
	 */
	@ArchTest
	static final ArchRule AOP_MUST_NOT_DEPEND_ON_BEAN_FACTORY_IMPLEMENTATIONS = noClasses().that()
		.resideInAPackage("org.springframework.aop..")
		.should()
		.dependOnClassesThat()
		.resideInAPackage("org.springframework.beans.factory.support..")
		.allowEmptyShould(true)
		.because("增强逻辑只该依赖接口，不该钉在某一种容器实现上");

	/**
	 * {@code aop} 不得依赖 {@code context}。
	 * <p>
	 * 代理只需要问容器「这个 Bean 现在是什么状态」，那是 {@code BeanFactory} 接口的事； 一旦某处 import 了
	 * {@code ApplicationContext}，AOP 就被钉死在「有上下文」的场景里。
	 */
	@ArchTest
	static final ArchRule AOP_MUST_NOT_DEPEND_ON_CONTEXT = noClasses().that()
		.resideInAPackage("org.springframework.aop..")
		.should()
		.dependOnClassesThat()
		.resideInAPackage("org.springframework.context..")
		.allowEmptyShould(true)
		.because("增强逻辑面向 BeanFactory，不面向 ApplicationContext");

	/**
	 * {@code core} 不得依赖任何上层包。
	 * <p>
	 * {@code core} 是全书地基：资源、类型转换、排序、异常。它一旦回头依赖 {@code beans}， 第 33
	 * 章那条「异常层次」的讲解就会立刻变成循环引用。
	 */
	@ArchTest
	static final ArchRule CORE_MUST_NOT_DEPEND_ON_UPPER_LAYERS = noClasses().that()
		.resideInAPackage("org.springframework.core..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("org.springframework.beans..", "org.springframework.context..", "org.springframework.aop..")
		.allowEmptyShould(true)
		.because("core 是地基，地基不能回头依赖楼层");

}
