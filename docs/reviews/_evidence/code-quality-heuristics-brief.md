# Checkable heuristics: canon Java/code-quality rules, scoped to a 200-class IoC/AOP reimplementation (JDK 17, zero deps)

## 1. Effective Java 3e — your item numbers, corrected

Numbering verified against the official Contents (all 90 items, with page numbers) in Pearson's sample PDF: <https://www.informit.com/content/images/9780134685991/samplepages/9780134685991_CH05.pdf>; chapter→item ranges cross-checked against Bloch's companion repo <https://github.com/jbloch/effective-java-3e-source-code>.

**Four numbers you got wrong:**

| You wrote | Actually |
|---|---|
| String over other types (63) | **62** *Avoid strings where other types are more appropriate*; 63 is *Beware the performance of string concatenation* |
| standard functional interfaces (59–61) | **44** *Favor the use of standard functional interfaces*; 59–61 = *Know and use the libraries* / *Avoid float and double* / *Prefer primitive types to boxed primitives* |
| generics/unchecked (27,32) | right, but the anchor is **26** *Don't use raw types*; 27 *Eliminate unchecked warnings*, 32 *Combine generics and varargs judiciously* |
| designer interfaces (21) | 21 = *Design interfaces for posterity* (never add methods to a published interface) |

Confirmed: 1, 2, 3, 5, 8, 9, 10, 11, 12, 17, 18, 19, 20, 24 (*Favor static member classes over nonstatic*), 34, 53, 54 (*Return empty collections or arrays, not nulls*), 55, 57, 58, 64, 65, 69, 70, 71, 72, 73, 74, 75, 76 (*Strive for failure atomicity*), 77, 78, 79 (*Avoid excessive synchronization*), 80, 81, 82, 83, 84, 85, 88.

**Omitted items that bite here:** 4 *Enforce noninstantiability* (utility classes); 15 *Minimize accessibility* (factory internals); 49 *Check parameters*, 50 *Make defensive copies* (alias caches); 56 *Doc comments for exposed API*; 40 *Consistently use @Override*; 42–43 *Lambdas / method references* (advice, `BeanPostProcessor`). 83 governs double-checked locking if you add lazy init.

## 2. Clean Code vs. A Philosophy of Software Design

**Clean Code (Martin).** "The first rule of functions is that they should be small. The second rule … is that *they should be smaller than that*"; "hardly ever be 20 lines long"; blocks inside `if`/`else`/`while` "should be one line long" (pp. 34–35, quoted verbatim by Ousterhout): <https://github.com/johnousterhout/aposd-vs-clean-code>. "Functions should do one thing. They should do it well. They should do it only."; "The ideal number of arguments for a function is zero (niladic)… Three arguments (triadic) should be avoided where possible": <https://www.ersoftware.nl/clean-code/3-functions/>. Naming — intention-revealing, no disinformation, pronounceable, searchable: <https://www.ersoftware.nl/clean-code/2-meaningful-names/>. Comments — "compensate for our failure to express ourselves in code… **Comments are always failures**… you should grimace" (p. 54, same repo URL). Martin's defence: the chapter opens "*Nothing can be quite so helpful as a well placed comment*".

**Ousterhout (APOSD) and the explicit rebuttal.** Symptoms — "**Change amplification**: a simple change requires many code modifications. **Cognitive load** … **Unknown unknowns**": his own CS190 notes <https://web.stanford.edu/~ouster/cgi-bin/cs190-winter18/lecture.php?topic=complexity>; book site <https://web.stanford.edu/~ouster/cgi-bin/book.php>. Deep/shallow — "The best methods are those that provide a lot of functionality but have a very simple interface… I call these methods 'deep'… As methods get smaller … the interfaces often become more complex. I call these interfaces 'shallow'"; plus "decomposition can be taken too far … it tends to result in *entanglement*". Comments rebuttal, verbatim: "I have to be honest: **I was horrified** when I first read this text, and it still makes me cringe. This stigmatizes writing comments"; "there is a lot of important information that simply cannot be expressed in code… **This is not a 'failure of their ability to express themselves'**". Comments-as-design: "Comments should describe things that are not obvious from the code"; interface docs must never describe the implementation <https://web.stanford.edu/~ouster/cgi-bin/cs190-winter18/lecture.php?topic=comments>. Full 2024–25 exchange: <https://groups.google.com/g/software-design-book/c/yHsxTAAqP9g>.

## 3. Spring Framework's own code style

Page: <https://github.com/spring-projects/spring-framework/wiki/Code-Style> (same text in-repo: <https://github.com/spring-projects/spring-framework/blob/main/CONTRIBUTING.md#source-code-style>). Checkable: wildcard imports **forbidden, even in test code**; indentation **tabs**, UTF-8, LF, no trailing whitespace; **`@since` on every new class** and every new public/protected method of an existing class, minor `.0` dropped (`@since 7.1`); type-level tag order `@author, @since, @param, @see, @deprecated`; imperative Javadoc first sentence ("Return…", not "Returns…"); utility classes `abstract` + `private` constructor; `var` banned in main code; `System.out`/`printStackTrace()` banned.

**Your "no `@author`" is backwards**: Spring's own class-Javadoc example carries `@author Rob Harrop`, and Checkstyle enforces it — `JavadocTypeCheck` with `authorFormat=".+\s.+"`, `AtclauseOrderCheck` starting at `@author`: <https://github.com/spring-projects/spring-framework/blob/main/src/checkstyle/checkstyle.xml>. It also hard-codes `AvoidStarImport`, `UnusedImports`, `ImportOrderCheck`, `HideUtilityClassConstructorCheck`, `EqualsHashCodeCheck`, `MissingOverrideCheck`, `NeedBracesCheck`, `EmptyCatchBlockCheck`, `RequireThisCheck`. Formatting is machine-applied by **spring-javaformat** (the config imports `io.spring.javaformat.checkstyle.*`); tool: <https://github.com/spring-io/spring-javaformat> — `mvn spring-javaformat:apply` is its Maven binding.

## 4. Google Java Style + Oracle/Sun

Google <https://google.github.io/styleguide/javaguide.html>: **2-space indent, tabs forbidden** (§2.3.1, 4.2); **100 columns** (4.4); no wildcard imports (3.3.1); Javadoc for every visible member (7.3); `@Override` always (6.1); no `finalize` (6.4). Oracle/Sun archival <https://www.oracle.com/java/technologies/javase/codeconventions-indentation.html>: **4-space indent, 8-space continuation, 80 columns**; naming — class nouns in mixed case, constants `UPPER_SNAKE`, no leading `_`/`$` <https://www.oracle.com/java/technologies/javase/codeconventions-namingconventions.html>.

**Irreconcilable:** indentation (Spring tabs / Google 2 / Sun 4) and width (Spring ≤120 with ~80 Javadoc / Google 100 / Sun 80). Pick Spring's if you mirror Spring's code; Google's if you run `google-java-format`. On `@author` and rich Javadoc, Spring+Google's documentation culture sits with Ousterhout, against "comments are failures".

*Note: github.com HTML was unreachable from this session; the wiki wording was confirmed via a search-indexed copy of that page plus the in-repo section (fetched). Everything else was fetched directly.*
