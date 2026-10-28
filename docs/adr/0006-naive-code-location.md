# ADR-0006：故意朴素的对照代码放在 `beans.factory.naive`，并对闸门显式豁免

- **状态**：已接受
- **日期**：2026-10-28
- **决策者**：LandFairy

## 背景

`docs/spec/0001` 的「豁免机制」一节写的是：

> **豁免机制**：故意朴素的对照代码只允许放在 `demo/naive/**`，该路径对 PMD/Checkstyle
> 显式豁免，且每个文件必须有标注"教学用反面示例"的 Javadoc。例外必须被标注，不能默默存在。

实际代码放的是 `org.springframework.beans.factory.naive.NaiveContainer`
（第 01 章那个「只有一张 Map」的容器）。两处不一致：

1. 路径不是 `demo/naive/**`；
2. 既没有豁免配置，也没有「教学用反面示例」的标注——也就是说，
   这个例外一直在**默默存在**，正是 spec 要禁止的那件事。

## 决策

**保留包名 `org.springframework.beans.factory.naive`，补上标注与豁免。** 理由：

- `ADR-0001` 要求所有框架类都在 `org.springframework.*` 下，与真实 Spring 对齐。
  一个 `demo.naive` 包会立刻破坏这条规矩，而这条规矩的价值（读者能在真实源码里找到同名类）
  高于「路径与 spec 字面一致」。
- `naive` 作为**包名后缀**比 `demo/naive` 更精确：它标明的是「这是同一个概念的朴素实现」，
  而不是「这是演示」。

配套三项动作：

1. 每个 `NaiveContainer`（共 35 份）的类级 Javadoc 加一行
   `@implNote 教学用反面示例：故意保留朴素写法，不要照抄进生产代码。`
   —— 用固定措辞，便于全仓 `grep`。
2. `config/pmd/pmd-ruleset.xml` 加 `<exclude-pattern>.*[/\\]naive[/\\].*</exclude-pattern>`。
3. `config/checkstyle/checkstyle.xml` 加 `SuppressionFilter`，指向
   `config/checkstyle/suppressions.xml`，其中豁免 `[\\/]naive[\\/]` 路径。

## 后果

- **正面**：例外从「默默存在」变成「一句话能查出来」。新读者 `grep 教学用反面示例` 就能找到全部反面教材。
- **正面**：闸门配置里第一次出现了「豁免」这个概念，正文可以顺带讲清
  「静态分析工具最怕的不是规则少，而是豁免没有记录」。
- **负面**：spec 的字面路径（`demo/naive/**`）与实际不一致，需要在
  `docs/spec/0001` 与 README 里各留一句说明，否则下一个人还会踩。

## 验证

- `./mvnw clean verify` 全绿。
- 反向验证：在 `naive` 包里故意写一个触发 `UnusedPrivateField` 的字段，`verify` 仍绿；
  同样的字段写到 `naive` 包外，`verify` 立刻判红。
