package org.springframework.samples.ch17;

import org.springframework.context.annotation.ComponentScan;

/**
 * 一个「声明扫描范围」的类。
 *
 * <p>
 * 它自己<b>不是</b>组件（没有 {@code @Component}），所以扫描不会把它注册成 Bean； 它存在的意义是让读者看到
 * {@code @ComponentScan} 的值长什么样、以及读字节码时 怎么把属性值读出来。
 */
@ComponentScan("org.springframework.samples.ch17")
public class ScannedConfiguration {

}
