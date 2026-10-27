package org.springframework.samples.ch22;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 导入三样东西：一个普通类、一个配置类、以及「重复导入同一个类」——第三种情况验证幂等。
 */
@Configuration
@Import({ PlainImportedBean.class, ImportedConfiguration.class, PlainImportedBean.class })
public class ImportingConfiguration {

}
