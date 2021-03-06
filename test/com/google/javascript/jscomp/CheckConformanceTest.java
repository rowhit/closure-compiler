/*
 * Copyright 2014 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.javascript.jscomp.CheckConformance.InvalidRequirementSpec;
import com.google.javascript.jscomp.CompilerOptions.LanguageMode;
import com.google.javascript.jscomp.ConformanceRules.AbstractRule;
import com.google.javascript.jscomp.ConformanceRules.ConformanceResult;
import com.google.javascript.jscomp.testing.BlackHoleErrorManager;
import com.google.javascript.rhino.Node;
import com.google.protobuf.TextFormat;
import com.google.protobuf.TextFormat.ParseException;
import java.util.List;

/**
 * Tests for {@link CheckConformance}.
 *
 */
public final class CheckConformanceTest extends TypeICompilerTestCase {
  private String configuration;

  private static final String EXTERNS =
      LINE_JOINER.join(
          DEFAULT_EXTERNS,
          "/** @constructor */ function Window() {};",
          "/** @type {Window} */ var window;",
          "/** @type {Function} */ Arguments.prototype.callee;",
          "/** @type {Function} */ Arguments.prototype.caller;",
          "/** @type {Arguments} */ var arguments;",
          "/** @constructor ",
          " * @param {*=} opt_message",
          " * @param {*=} opt_file",
          " * @param {*=} opt_line",
          " * @return {!Error}",
          "*/",
          "function Error(opt_message, opt_file, opt_line) {};",
          "function alert(y) {};",
          "/** @constructor */ function ObjectWithNoProps() {};",
          "function eval() {}");

  private static final String DEFAULT_CONFORMANCE =
      LINE_JOINER.join(
          "requirement: {",
          "  type: BANNED_NAME",
          "  value: 'eval'",
          "   error_message: 'eval is not allowed'",
          "}",
          "",
          "requirement: {",
          "  type: BANNED_PROPERTY",
          "  value: 'Arguments.prototype.callee'",
          "  error_message: 'Arguments.prototype.callee is not allowed'",
          "}");

  public CheckConformanceTest() {
    super(EXTERNS, true);
    enableTranspile();
    enableNormalize();
    enableClosurePass();
    enableClosurePassForExpected();
    enableRewriteClosureCode();
    setLanguage(LanguageMode.ECMASCRIPT6_STRICT, LanguageMode.ECMASCRIPT5_STRICT);
  }

  @Override
  protected CompilerOptions getOptions() {
    CompilerOptions options = super.getOptions();
    options.setWarningLevel(
        DiagnosticGroups.MISSING_PROPERTIES, CheckLevel.OFF);
    options.setCodingConvention(getCodingConvention());
    return options;
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    super.enableTypeCheck();
    super.enableClosurePass();
    configuration = DEFAULT_CONFORMANCE;
  }

  @Override
  public CompilerPass getProcessor(final Compiler compiler) {
    ConformanceConfig.Builder builder = ConformanceConfig.newBuilder();
    try {
      TextFormat.merge(configuration, builder);
    } catch (ParseException e) {
      throw new RuntimeException(e);
    }
    return new CheckConformance(compiler, ImmutableList.of(builder.build()));
  }

  @Override
  public int getNumRepetitions() {
    // This compiler pass is not idempotent and should only be run over a
    // parse tree once.
    return 1;
  }

  public void testViolation1() {
    configuration =
        "requirement: {\n" +
        "  type: BANNED_NAME\n" +
        "  value: 'eval'\n" +
        "  error_message: 'eval is not allowed'\n" +
        "}";

    testSame(
        "eval()",
        CheckConformance.CONFORMANCE_VIOLATION);

    testSame(
        "Function.prototype.name; eval.name.length",
        CheckConformance.CONFORMANCE_VIOLATION);
  }

  public void testViolation2() {
    testSame(
        "function f() { arguments.callee }",
        CheckConformance.CONFORMANCE_VIOLATION);
  }

  public void testNotViolation1() {
    testSame(
        "/** @constructor */ function Foo() { this.callee = 'string'; }\n" +
        "/** @constructor */ function Bar() { this.callee = 1; }\n" +
        "\n" +
        "\n" +
        "function f() {\n" +
        "  var x;\n" +
        "  switch(random()) {\n" +
        "    case 1:\n" +
        "      x = new Foo();\n" +
        "      break;\n" +
        "    case 2:\n" +
        "      x = new Bar();\n" +
        "      break;\n" +
        "    default:\n" +
        "      return;\n" +
        "  }\n" +
        "  var z = x.callee;\n" +
        "}");
  }

  public void testNotViolation2() {
    configuration =
        "requirement: {\n"
            + "  type: BANNED_NAME\n"
            + "  value: 'location'\n"
            + "  error_message: 'location is not allowed'\n"
            + "}";
    testSame("function f() { var location = null; }");
  }

  public void testMaybeViolation1() {
    testSame(
        "function f() { y.callee }",
        CheckConformance.CONFORMANCE_POSSIBLE_VIOLATION);

    testSame(
        "function f() { new Foo().callee }",
        CheckConformance.CONFORMANCE_POSSIBLE_VIOLATION);

    testSame(
        "function f() { new Object().callee }",
        CheckConformance.CONFORMANCE_POSSIBLE_VIOLATION);

    // NTI warns since "callee" doesn't exist on *
    testSame(
        "/** @suppress {newCheckTypes} */ function f() { /** @type {*} */ var x; x.callee }",
        CheckConformance.CONFORMANCE_POSSIBLE_VIOLATION);

    testSame("function f() {/** @const */ var x = {}; x.callee = 1; x.callee}");
  }

  public void testBadWhitelist1() {
    allowSourcelessWarnings();
    configuration =
        "requirement: {\n" +
        "  type: BANNED_NAME\n" +
        "  value: 'eval'\n" +
        "  error_message: 'placeholder'\n" +
        "  whitelist_regexp: '('\n" +
        "}";

    testError(
        "anything;",
        CheckConformance.INVALID_REQUIREMENT_SPEC,
        "Invalid requirement. Reason: invalid regex pattern\n"
            + "Requirement spec:\n"
            + "error_message: \"placeholder\"\n"
            + "whitelist_regexp: \"(\"\n"
            + "type: BANNED_NAME\n"
            + "value: \"eval\"\n");
  }

  public void testViolationWhitelisted1() {
    configuration =
        "requirement: {\n" +
        "  type: BANNED_NAME\n" +
        "  value: 'eval'\n" +
        "  error_message: 'eval is not allowed'\n" +
        "  whitelist: 'testcode'\n " +
        "}";

    testSame(
        "eval()");
  }

  public void testViolationWhitelisted2() {
    configuration =
        "requirement: {\n" +
        "  type: BANNED_NAME\n" +
        "  value: 'eval'\n" +
        "  error_message: 'eval is not allowed'\n" +
        "  whitelist_regexp: 'code$'\n " +
        "}";

    testSame(
        "eval()");
  }

  public void testFileOnOnlyApplyToIsChecked() {
    configuration =
        "requirement: {\n" +
        "  type: BANNED_NAME\n" +
        "  value: 'eval'\n" +
        "  error_message: 'eval is not allowed'\n" +
        "  only_apply_to: 'foo.js'\n " +
        "}";
    ImmutableList<SourceFile> input = ImmutableList.of(
            SourceFile.fromCode("foo.js", "eval()"));
    test(input, input, null, CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: eval is not allowed");
  }

  public void testFileNotOnOnlyApplyToIsNotChecked() {
    configuration =
        "requirement: {\n" +
        "  type: BANNED_NAME\n" +
        "  value: 'eval'\n" +
        "  error_message: 'eval is not allowed'\n" +
        "  only_apply_to: 'foo.js'\n " +
        "}";
    testSame(ImmutableList.of(SourceFile.fromCode("bar.js", "eval()")));
  }

  public void testFileOnOnlyApplyToRegexpIsChecked() {
    configuration =
        "requirement: {\n" +
        "  type: BANNED_NAME\n" +
        "  value: 'eval'\n" +
        "  error_message: 'eval is not allowed'\n" +
        "  only_apply_to_regexp: 'test.js$'\n " +
        "}";
    ImmutableList<SourceFile> input = ImmutableList.of(
            SourceFile.fromCode("foo_test.js", "eval()"));
    test(input, input, null, CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: eval is not allowed");
  }

  public void testFileNotOnOnlyApplyToRegexpIsNotChecked() {
    configuration =
        "requirement: {\n" +
        "  type: BANNED_NAME\n" +
        "  value: 'eval'\n" +
        "  error_message: 'eval is not allowed'\n" +
        "  only_apply_to_regexp: 'test.js$'\n " +
        "}";
    testSame(ImmutableList.of(SourceFile.fromCode("bar.js", "eval()")));
  }

  public void testInferredConstCheck() {
    configuration =
        LINE_JOINER.join(
            "requirement: {",
            "  type: CUSTOM",
            "  java_class: 'com.google.javascript.jscomp.ConformanceRules$InferredConstCheck'",
            "  error_message: 'Failed to infer type of constant'",
            "}");

    testSame("/** @const */ var x = 0;");

    // We @suppress {newCheckTypes} to suppress NTI uninferred const and global this warnings.

    testConformance(
        LINE_JOINER.join(
            "/** @constructor @suppress {newCheckTypes} */",
            "function f() {",
            "  /** @const */ this.foo = unknown;",
            "}",
            "var x = new f();"),
        CheckConformance.CONFORMANCE_VIOLATION);

    testConformance(
        LINE_JOINER.join(
            "/** @constructor */",
            "function f() {}",
            "/** @this {f} @suppress {newCheckTypes} */",
            "var init_f = function() {",
            "  /** @const */ this.foo = unknown;",
            "};",
            "var x = new f();"),
        CheckConformance.CONFORMANCE_VIOLATION);

    testConformance(
        LINE_JOINER.join(
            "/** @constructor */",
            "function f() {}",
            "/** @suppress {newCheckTypes} */",
            "var init_f = function() {",
            "  /** @const */ this.FOO = unknown;",
            "};",
            "var x = new f();"),
        CheckConformance.CONFORMANCE_VIOLATION);

    testConformance(
        LINE_JOINER.join(
            "/** @constructor */",
            "function f() {}",
            "/** @suppress {newCheckTypes} */",
            "f.prototype.init_f = function() {",
            "  /** @const */ this.FOO = unknown;",
            "};",
            "var x = new f();"),
        CheckConformance.CONFORMANCE_VIOLATION);

    testSame(
        LINE_JOINER.join(
            "/** @constructor */",
            "function f() {}",
            "f.prototype.init_f = function() {",
            "  /** @const {?} */ this.FOO = unknown;",
            "};",
            "var x = new f();"));

    testSame(
        LINE_JOINER.join(
            "/** @const */",
            "var ns = {};",
            "/** @const */",
            "ns.subns = ns.subns || {};"));
  }

  public void testBannedCodePattern1() {
    configuration =
        "requirement: {\n" +
        "  type: BANNED_CODE_PATTERN\n" +
        "  value: '/** @param {string|String} a */" +
                  "function template(a) {a.blink}'\n" +
        "  error_message: 'blink is annoying'\n" +
        "}";

    String externs = EXTERNS + "String.prototype.blink;";

    testSame(
        "/** @constructor */ function Foo() { this.blink = 1; }\n" +
        "var foo = new Foo();\n" +
        "foo.blink();");

    testSame(
        externs,
        "'foo'.blink;",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: blink is annoying");

    testSame(
        externs,
        "'foo'.blink();",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: blink is annoying");

    testSame(
        externs,
        "String('foo').blink();",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: blink is annoying");

    testSame(
        externs,
        "foo.blink();",
        CheckConformance.CONFORMANCE_POSSIBLE_VIOLATION,
        "Possible violation: blink is annoying\n"
        + "The type information available for this expression is too loose to ensure conformance.");
  }

  public void testBannedDep1() {
    configuration =
        "requirement: {\n" +
        "  type: BANNED_DEPENDENCY\n" +
        "  value: 'testcode'\n" +
        "  error_message: 'testcode is not allowed'\n" +
        "}";

    testWarning(
        "anything;", CheckConformance.CONFORMANCE_VIOLATION, "Violation: testcode is not allowed");
  }

  public void testReportLooseTypeViolations() {
    configuration =
        LINE_JOINER.join(
            "requirement: {",
            "  type: BANNED_PROPERTY_WRITE",
            "  value: 'HTMLScriptElement.prototype.textContent'",
            "  error_message: 'Setting content of <script> is dangerous.'",
            "  report_loose_type_violations: false",
            "}");

    String externs =
        LINE_JOINER.join(
            DEFAULT_EXTERNS,
            "/** @constructor */ function Element() {}",
            "/** @type {string} @implicitCast */",
            "Element.prototype.textContent;",
            "/** @constructor @extends {Element} */ function HTMLScriptElement() {}\n");

    testSame(
        externs,
        "(new HTMLScriptElement).textContent = 'alert(1);'",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: Setting content of <script> is dangerous.");

    testSame(
        externs,
        "HTMLScriptElement.prototype.textContent = 'alert(1);'",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: Setting content of <script> is dangerous.");

    // NOTE(aravindpg): we define a random extra property on HTMLScriptElement.prototype so
    // as to differentiate from any old (new Element), otherwise NTI is unable to distinguish them
    // and treats this case exactly like the case right above.
    // This limitation is arguably never a problem in practice since we always define/redefine
    // properties on a subclass.
    testSame(
        externs,
        "HTMLScriptElement.prototype.randomProp; (new Element).textContent = 'safe'",
        null);
  }

  private void testConformance(String src, DiagnosticType warning) {
    testConformance(src, "", warning);
  }

  private void testConformance(String src1, String src2) {
    testConformance(src1, src2, null);
  }

  private void testConformance(String src1, String src2, DiagnosticType warning) {
    ImmutableList<SourceFile> input = ImmutableList.of(
            SourceFile.fromCode("SRC1", src1),
            SourceFile.fromCode("SRC2", src2));
    test(input, input, null, warning);
  }

  public void testBannedProperty0() {
    configuration = LINE_JOINER.join(
        "requirement: {",
        "  type: BANNED_PROPERTY",
        "  value: 'C.prototype.p'",
        "  error_message: 'C.p is not allowed'",
        "  whitelist: 'SRC1'",
        "}");

    String cDecl = LINE_JOINER.join(
        "/** @constructor */",
        "function C() {}",
        "/** @type {string} */",
        "C.prototype.p;");

    String dDecl = LINE_JOINER.join(
        "/** @constructor */ function D() {}",
        "/** @type {string} */",
        "D.prototype.p;");

    testConformance(cDecl, dDecl);
  }

  public void testBannedProperty1() {
    configuration = LINE_JOINER.join(
        "requirement: {",
        "  type: BANNED_PROPERTY",
        "  value: 'C.prototype.p'",
        "  error_message: 'C.p is not allowed'",
        "  whitelist: 'SRC1'",
        "}");

    String cDecl = LINE_JOINER.join(
        "/** @constructor */",
        "function C() {",
        "  this.p = 'str';",
        "}");

    String dDecl = LINE_JOINER.join(
        "/** @constructor */",
        "function D() {",
        "  this.p = 'str';",
        "}");

    testConformance(cDecl, dDecl);
  }

  public void testBannedProperty2() {
    configuration = LINE_JOINER.join(
        "requirement: {",
        "  type: BANNED_PROPERTY",
        "  value: 'C.prototype.p'",
        "  error_message: 'C.p is not allowed'",
        "  whitelist: 'SRC1'",
        "}");

    String declarations = LINE_JOINER.join(
        "/** @constructor */ function SC() {}",
        "/** @constructor @extends {SC} */",
        "function C() {}",
        "/** @type {string} */",
        "C.prototype.p;",
        "/** @constructor */ function D() {}",
        "/** @type {string} */",
        "D.prototype.p;");

    testConformance(declarations, "var d = new D(); d.p = 'boo';");

    testConformance(declarations, "var c = new C(); c.p = 'boo';",
        CheckConformance.CONFORMANCE_VIOLATION);

    // Accessing property through a super type is possibily a violation.
    testConformance(declarations, "var sc = new SC(); sc.p = 'boo';",
        CheckConformance.CONFORMANCE_POSSIBLE_VIOLATION);

    testConformance(declarations, "var c = new C(); var foo = c.p;",
        CheckConformance.CONFORMANCE_VIOLATION);

    testConformance(declarations, "var c = new C(); var foo = 'x' + c.p;",
        CheckConformance.CONFORMANCE_VIOLATION);

    testConformance(declarations, "var c = new C(); c['p'] = 'boo';",
        CheckConformance.CONFORMANCE_VIOLATION);
  }

  public void testBannedProperty3() {
    configuration = LINE_JOINER.join(
        "requirement: {",
        "  type: BANNED_PROPERTY",
        "  value: 'C.prototype.p'",
        "  error_message: 'C.p is not allowed'",
        "  whitelist: 'SRC1'",
        "}");

    String cdecl = LINE_JOINER.join(
        "/** @constructor */ function SC() {}",
        "/** @constructor @extends {SC} */",
        "function C() {}",
        "/** @type {string} */",
        "C.prototype.p;");
    String ddecl = LINE_JOINER.join(
        "/** @constructor @template T */ function D() {}",
        "/** @suppress {newCheckTypes} @param {T} a */",
        "D.prototype.method = function(a) {",
        "  use(a.p);",
        "};");

    testConformance(cdecl, ddecl,
        CheckConformance.CONFORMANCE_POSSIBLE_VIOLATION);
  }

  public void testBannedProperty4() {
    configuration = LINE_JOINER.join(
        "requirement: {",
        "  type: BANNED_PROPERTY",
        "  value: 'C.prototype.p'",
        "  error_message: 'C.p is not allowed'",
        "  whitelist: 'SRC1'",
        "}");

    String cdecl = LINE_JOINER.join(
        "/** @constructor */ function SC() {}",
        "/** @constructor @extends {SC} */",
        "function C() {}",
        "/** @type {string} */",
        "C.prototype.p;",
        "",
        "/**",
        " * @param {K} key",
        " * @param {V=} opt_value",
        " * @constructor",
        " * @struct",
        " * @template K, V",
        " * @private",
        " */",
        "var Entry_ = function(key, opt_value) {",
        "  /** @const {K} */",
        "  this.key = key;",
        "  /** @type {V|undefined} */",
        "  this.value = opt_value;",
        "};");

    String ddecl = LINE_JOINER.join(
        "/** @constructor @template T */ function D() {}",
        "/** @param {T} a */",
        "D.prototype.method = function(a) {",
        "  var entry = new Entry('key');",
        "  use(entry.value.p);",
        "};");

    testConformance(cdecl, ddecl,
        CheckConformance.CONFORMANCE_POSSIBLE_VIOLATION);
  }

  public void testBannedPropertyWrite() {
    configuration =
        "requirement: {\n" +
        "  type: BANNED_PROPERTY_WRITE\n" +
        "  value: 'C.prototype.p'\n" +
        "  error_message: 'Assignment to C.p is not allowed'\n" +
        "}";

    String declarations =
        "/** @constructor */ function C() {}\n" +
        "/** @type {string} */\n" +
        "C.prototype.p;\n" +
        "/** @constructor */ function D() {}\n" +
        "/** @type {string} */\n" +
        "D.prototype.p;\n";

    testSame(
        declarations + "var d = new D(); d.p = 'boo';");

    testSame(
        declarations + "var c = new C(); c.p = 'boo';",
        CheckConformance.CONFORMANCE_VIOLATION);

    testSame(
        declarations + "var c = new C(); var foo = c.p;");

    testSame(
        declarations + "var c = new C(); var foo = 'x' + c.p;");

    testSame(
        declarations + "var c = new C(); c['p'] = 'boo';",
        CheckConformance.CONFORMANCE_VIOLATION);
  }

  public void testBannedPropertyWriteExtern() {
    configuration =
        "requirement: {\n" +
        "  type: BANNED_PROPERTY_WRITE\n" +
        "  value: 'Element.prototype.innerHTML'\n" +
        "  error_message: 'Assignment to Element.innerHTML is not allowed'\n" +
        "}";

    String externs =
        DEFAULT_EXTERNS
        + "/** @constructor */ function Element() {}\n" +
        "/** @type {string} @implicitCast */\n" +
        "Element.prototype.innerHTML;\n";

    testSame(
        externs,
        "var e = new Element(); e.innerHTML = '<boo>';",
        CheckConformance.CONFORMANCE_VIOLATION);

    testSame(
        externs,
        "var e = new Element(); e.innerHTML = 'foo';",
        CheckConformance.CONFORMANCE_VIOLATION);

    testSame(
        externs,
        "var e = new Element(); e['innerHTML'] = 'foo';",
        CheckConformance.CONFORMANCE_VIOLATION);
  }

  public void testBannedPropertyNonConstantWrite() {
    configuration =
        "requirement: {\n" +
        "  type: BANNED_PROPERTY_NON_CONSTANT_WRITE\n" +
        "  value: 'C.prototype.p'\n" +
        "  error_message: 'Assignment of a non-constant value to C.p is not allowed'\n" +
        "}";

    String declarations =
        "/** @constructor */ function C() {}\n" +
        "/** @type {string} */\n" +
        "C.prototype.p;\n";

    testSame(declarations + "var c = new C(); c.p = 'boo';");
    testSame(declarations + "var c = new C(); c.p = 'foo' + 'bar';");

    testSame(
        declarations + "var boo = 'boo'; var c = new C(); c.p = boo;",
        CheckConformance.CONFORMANCE_VIOLATION);
  }

  public void testBannedPropertyRead() {
    configuration =
        "requirement: {\n" +
        "  type: BANNED_PROPERTY_READ\n" +
        "  value: 'C.prototype.p'\n" +
        "  error_message: 'Use of C.p is not allowed'\n" +
        "}";

    String declarations =
        "/** @constructor */ function C() {}\n" +
        "/** @type {string} */\n" +
        "C.prototype.p;\n" +
        "/** @constructor */ function D() {}\n" +
        "/** @type {string} */\n" +
        "D.prototype.p;\n" +
        "function use(a) {};";

    testSame(
        declarations + "var d = new D(); d.p = 'boo';");

    testSame(
        declarations + "var c = new C(); c.p = 'boo';");

    testSame(
        declarations + "var c = new C(); use(c.p);",
        CheckConformance.CONFORMANCE_VIOLATION);

    testSame(
        declarations + "var c = new C(); var foo = c.p;",
        CheckConformance.CONFORMANCE_VIOLATION);

    testSame(
        declarations + "var c = new C(); var foo = 'x' + c.p;",
        CheckConformance.CONFORMANCE_VIOLATION);

    testSame(
        declarations + "var c = new C(); c['p'] = 'boo';");

    testSame(
        declarations + "var c = new C(); use(c['p']);",
        CheckConformance.CONFORMANCE_VIOLATION);
  }

  public void testRestrictedCall1() {
    configuration =
        "requirement: {\n" +
        "  type: RESTRICTED_METHOD_CALL\n" +
        "  value: 'C.prototype.m:function(number)'\n" +
        "  error_message: 'm method param must be number'\n" +
        "}";

    String code =
        "/** @constructor */ function C() {}\n" +
        "/** @param {*} a */\n" +
        "C.prototype.m = function(a){}\n";

    testSame(
        code + "new C().m(1);");

    testSame(
        code + "new C().m('str');",
        CheckConformance.CONFORMANCE_VIOLATION);

    testSame(
        code + "new C().m.call(new C(), 1);");

    testSame(
        code + "new C().m.call(new C(), 'str');",
        CheckConformance.CONFORMANCE_VIOLATION);
  }

  public void testRestrictedCall2() {
    configuration =
        "requirement: {\n" +
        "  type: RESTRICTED_NAME_CALL\n" +
        "  value: 'C.m:function(number)'\n" +
        "  error_message: 'C.m method param must be number'\n" +
        "}";

    String code =
        "/** @constructor */ function C() {}\n" +
        "/** @param {*} a */\n" +
        "C.m = function(a){}\n";

    testSame(
        code + "C.m(1);");

    testSame(
        code + "C.m('str');",
        CheckConformance.CONFORMANCE_VIOLATION);

    testSame(
        code + "C.m.call(this, 1);");

    testSame(
        code + "C.m.call(this, 'str');",
        CheckConformance.CONFORMANCE_VIOLATION);
  }

  public void testRestrictedCall3() {
    configuration =
        "requirement: {\n" +
        "  type: RESTRICTED_NAME_CALL\n" +
        "  value: 'C:function(number)'\n" +
        "  error_message: 'C method must be number'\n" +
        "}";

    String code =
        "/** @constructor @param {...*} a */ function C(a) {}\n";

    testSame(
        code + "new C(1);");

    testSame(
        code + "new C('str');",
        CheckConformance.CONFORMANCE_VIOLATION);

    testSame(
        code + "new C(1, 1);",
        CheckConformance.CONFORMANCE_VIOLATION);

    testSame(
        code + "new C();",
        CheckConformance.CONFORMANCE_VIOLATION);
  }

  public void testRestrictedCall4() {
    configuration =
        "requirement: {\n" +
        "  type: RESTRICTED_NAME_CALL\n" +
        "  value: 'C:function(number)'\n" +
        "  error_message: 'C method must be number'\n" +
        "}";

    String code =
        "/** @constructor @param {...*} a */ function C(a) {}\n";

    testSame(
        EXTERNS + "goog.inherits;",
        code + "goog.inherits(A, C);",
        null);
  }

  public void testRestrictedMethodCallThisType() {
    configuration = ""
        + "requirement: {\n"
        + "  type: RESTRICTED_METHOD_CALL\n"
        + "  value: 'Base.prototype.m:function(this:Sub,number)'\n"
        + "  error_message: 'Only call m on the subclass'\n"
        + "}";

    String code =
        "/** @constructor */\n"
        + "function Base() {}; Base.prototype.m;\n"
        + "/** @constructor @extends {Base} */\n"
        + "function Sub() {}\n"
        + "var b = new Base();\n"
        + "var s = new Sub();\n"
        + "var maybeB = cond ? new Base() : null;\n"
        + "var maybeS = cond ? new Sub() : null;\n";

    testSame(code + "b.m(1)", CheckConformance.CONFORMANCE_VIOLATION);
    testSame(code + "maybeB.m(1)", CheckConformance.CONFORMANCE_VIOLATION);
    testSame(code + "s.m(1)");
    testSame(code + "maybeS.m(1)");
  }

  public void testRestrictedMethodCallUsingCallThisType() {
    configuration = ""
        + "requirement: {\n"
        + "  type: RESTRICTED_METHOD_CALL\n"
        + "  value: 'Base.prototype.m:function(this:Sub,number)'\n"
        + "  error_message: 'Only call m on the subclass'\n"
        + "}";

    String code =
        "/** @constructor */\n"
        + "function Base() {}; Base.prototype.m;\n"
        + "/** @constructor @extends {Base} */\n"
        + "function Sub() {}\n"
        + "var b = new Base();\n"
        + "var s = new Sub();\n"
        + "var maybeB = cond ? new Base() : null;\n"
        + "var maybeS = cond ? new Sub() : null;";

    testSame(code + "b.m.call(b, 1)", CheckConformance.CONFORMANCE_VIOLATION);
    testSame(code + "b.m.call(maybeB, 1)", CheckConformance.CONFORMANCE_VIOLATION);
    testSame(code + "b.m.call(s, 1)");
    testSame(code + "b.m.call(maybeS, 1)");
  }

  public void testCustom1() {
    allowSourcelessWarnings();
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  error_message: 'placeholder'\n" +
        "}";

    testError(
        "anything;",
        CheckConformance.INVALID_REQUIREMENT_SPEC,
        "Invalid requirement. Reason: missing java_class\n"
            + "Requirement spec:\n"
            + "error_message: \"placeholder\"\n"
            + "type: CUSTOM\n");
  }

  public void testCustom2() {
    allowSourcelessWarnings();
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'MissingClass'\n" +
        "  error_message: 'placeholder'\n" +
        "}";

    testError(
        "anything;",
        CheckConformance.INVALID_REQUIREMENT_SPEC,
        "Invalid requirement. Reason: JavaClass not found.\n"
            + "Requirement spec:\n"
            + "error_message: \"placeholder\"\n"
            + "type: CUSTOM\n"
            + "java_class: \"MissingClass\"\n");
  }

  public void testCustom3() {
    allowSourcelessWarnings();
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.CheckConformanceTest'\n" +
        "  error_message: 'placeholder'\n" +
        "}";

    testError(
        "anything;",
        CheckConformance.INVALID_REQUIREMENT_SPEC,
        "Invalid requirement. Reason: JavaClass is not a rule.\n"
            + "Requirement spec:\n"
            + "error_message: \"placeholder\"\n"
            + "type: CUSTOM\n"
            + "java_class: \"com.google.javascript.jscomp.CheckConformanceTest\"\n");
  }

  // A custom rule missing a callable constructor.
  public static class CustomRuleMissingPublicConstructor extends AbstractRule {
    CustomRuleMissingPublicConstructor(
        AbstractCompiler compiler, Requirement requirement)
            throws InvalidRequirementSpec {
      super(compiler, requirement);
      if (requirement.getValueCount() == 0) {
        throw new InvalidRequirementSpec("missing value");
      }
    }

    @Override
    protected ConformanceResult checkConformance(NodeTraversal t, Node n) {
      // Everything is ok.
      return ConformanceResult.CONFORMANCE;
    }
  }


  // A valid custom rule.
  public static class CustomRule extends AbstractRule {
    public CustomRule(AbstractCompiler compiler, Requirement requirement)
        throws InvalidRequirementSpec {
      super(compiler, requirement);
      if (requirement.getValueCount() == 0) {
        throw new InvalidRequirementSpec("missing value");
      }
    }

    @Override
    protected ConformanceResult checkConformance(NodeTraversal t, Node n) {
      // Everything is ok.
      return ConformanceResult.CONFORMANCE;
    }
  }

  // A valid custom rule.
  public static class CustomRuleReport extends AbstractRule {
    public CustomRuleReport(AbstractCompiler compiler, Requirement requirement)
        throws InvalidRequirementSpec {
      super(compiler, requirement);
      if (requirement.getValueCount() == 0) {
        throw new InvalidRequirementSpec("missing value");
      }
    }

    @Override
    protected ConformanceResult checkConformance(NodeTraversal t, Node n) {
      // Everything is ok.
      return n.isScript() ? ConformanceResult.VIOLATION
          : ConformanceResult.CONFORMANCE;
    }
  }

  public void testCustom4() {
    allowSourcelessWarnings();
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.CheckConformanceTest$" +
            "CustomRuleMissingPublicConstructor'\n" +
        "  error_message: 'placeholder'\n" +
        "}";

    testError(
        "anything;",
        CheckConformance.INVALID_REQUIREMENT_SPEC,
        "Invalid requirement. Reason: No valid class constructors found.\n"
            + "Requirement spec:\n"
            + "error_message: \"placeholder\"\n"
            + "type: CUSTOM\n"
            + "java_class: \"com.google.javascript.jscomp.CheckConformanceTest$"
            + "CustomRuleMissingPublicConstructor\"\n");
  }


  public void testCustom5() {
    allowSourcelessWarnings();
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.CheckConformanceTest$CustomRule'\n" +
        "  error_message: 'placeholder'\n" +
        "}";

    testError(
        "anything;",
        CheckConformance.INVALID_REQUIREMENT_SPEC,
        "Invalid requirement. Reason: missing value\n"
            + "Requirement spec:\n"
            + "error_message: \"placeholder\"\n"
            + "type: CUSTOM\n"
            + "java_class: \"com.google.javascript.jscomp.CheckConformanceTest$CustomRule\"\n");
  }

  public void testCustom6() {
    allowSourcelessWarnings();
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.CheckConformanceTest$CustomRule'\n" +
        "  value: 'placeholder'\n" +
        "  error_message: 'placeholder'\n" +
        "}";

    testSame(
        "anything;");
  }

  public void testCustom7() {
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.CheckConformanceTest$" +
        "CustomRuleReport'\n" +
        "  value: 'placeholder'\n" +
        "  error_message: 'CustomRule Message'\n" +
        "}";

    testWarning(
        "anything;", CheckConformance.CONFORMANCE_VIOLATION, "Violation: CustomRule Message");
  }

  public void testCustomBanExpose() {
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.ConformanceRules$BanExpose'\n" +
        "  error_message: 'BanExpose Message'\n" +
        "}";

    testWarning(
        "/** @expose */ var x;",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: BanExpose Message");
  }

  public void testCustomRestrictThrow1() {
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.ConformanceRules$BanThrowOfNonErrorTypes'\n" +
        "  error_message: 'BanThrowOfNonErrorTypes Message'\n" +
        "}";

    testWarning(
        "throw 'blah';",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: BanThrowOfNonErrorTypes Message");
  }

  public void testCustomRestrictThrow2() {
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.ConformanceRules$BanThrowOfNonErrorTypes'\n" +
        "  error_message: 'BanThrowOfNonErrorTypes Message'\n" +
        "}";

    testSame("throw new Error('test');");
  }

  public void testCustomBanUnknownThis1() {
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.ConformanceRules$BanUnknownThis'\n" +
        "  error_message: 'BanUnknownThis Message'\n" +
        "}";

    testWarning(
        "function f() {alert(this);}",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: BanUnknownThis Message");
  }

  // TODO(johnlenz): add a unit test for templated "this" values.

  public void testCustomBanUnknownThis2() {
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.ConformanceRules$BanUnknownThis'\n" +
        "  error_message: 'BanUnknownThis Message'\n" +
        "}";

    testSame(
        "/** @constructor */ function C() {alert(this);}");
  }

  public void testCustomBanUnknownThis3() {
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.ConformanceRules$BanUnknownThis'\n" +
        "  error_message: 'BanUnknownThis Message'\n" +
        "}";

    testSame(
        "function f() {alert(/** @type {Error} */(this));}");
  }

  public void testCustomBanUnknownThis4() {
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.ConformanceRules$BanUnknownThis'\n" +
        "  error_message: 'BanUnknownThis Message'\n" +
        "}";

    testSame(
        "function f() {goog.asserts.assertInstanceof(this, Error);}");
  }

  private static String config(String rule, String message, String... fields) {
    String result = "requirement: {\n"
        + "  type: CUSTOM\n"
        + "  java_class: '" + rule + "'\n";
    for (String field : fields) {
      result += field;
    }
    result += "  error_message: '" + message + "'\n" + "}";
    return result;
  }

  private static String rule(String rule) {
    return "com.google.javascript.jscomp.ConformanceRules$" + rule;
  }

  private static String value(String value) {
    return "  value: '" + value + "'\n";
  }

  public void testCustomBanUnknownThisProp1() {
    configuration = config(rule("BanUnknownDirectThisPropsReferences"), "My rule message");

    testWarning(
        "/** @constructor */ function f() {}; f.prototype.prop;"
            + "f.prototype.method = function() { alert(this.prop); }",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: My rule message");
  }

  public void testCustomBanUnknownThisProp2() {
    configuration = config(rule("BanUnknownDirectThisPropsReferences"), "My rule message");

    testSame(
        "/** @constructor */ function f() {}; f.prototype.prop;"
            + "f.prototype.method = function() { this.prop = foo; };");
  }

  public void testCustomBanUnknownProp1() {
    configuration =
        config(rule("BanUnknownTypedClassPropsReferences"), "My rule message", value("String"));

    testWarning(
        "/** @constructor */ function f() {}; f.prototype.prop;"
            + "f.prototype.method = function() { alert(this.prop); }",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: My rule message\nThe property \"prop\" on type \"f\"");
  }

  public void testCustomBanUnknownProp2() {
    configuration =
        config(rule("BanUnknownTypedClassPropsReferences"), "My rule message", value("String"));

    String js = LINE_JOINER.join(
        "Object.prototype.foobar;",
        " /** @param {ObjectWithNoProps} a */",
        "function f(a) { alert(a.foobar); };");

    this.mode = TypeInferenceMode.OTI_ONLY;
    testWarning(
        js,
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: My rule message\nThe property \"foobar\" on type \"(ObjectWithNoProps|null)\"");

    // TODO(aravindpg): Only difference is we don't add parens at the ends of our union type
    // string reprs in NTI. Fix them to be the same if possible.
    this.mode = TypeInferenceMode.NTI_ONLY;
    testWarning(
        js,
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: My rule message\nThe property \"foobar\" on type \"ObjectWithNoProps|null\"");
  }

  public void testCustomBanUnknownProp3() {
    configuration =
        config(rule("BanUnknownTypedClassPropsReferences"), "My rule message", value("String"));

    testSame(
        "/** @constructor */ function f() {}"
            + "f.prototype.method = function() { this.prop = foo; };");
  }

  public void testCustomBanUnknownProp4() {
    configuration =
        config(rule("BanUnknownTypedClassPropsReferences"), "My rule message", value("String"));

    testSame(
        LINE_JOINER.join(
            "/** @constructor */ function f() { /** @type {?} */ this.prop = null; };",
            "f.prototype.method = function() { alert(this.prop); }"));
  }

  public void testCustomBanUnknownProp5() {
    configuration =
        config(rule("BanUnknownTypedClassPropsReferences"), "My rule message", value("String"));

    testWarning(
        LINE_JOINER.join(
            "/** @typedef {?} */ var Unk;",
            "/** @constructor */ function f() { /** @type {?Unk} */ this.prop = null; };",
            "f.prototype.method = function() { alert(this.prop); }"),
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: My rule message\nThe property \"prop\" on type \"f\"");
  }

  public void testCustomBanUnknownProp6() {
    configuration =
        config(rule("BanUnknownTypedClassPropsReferences"), "My rule message", value("String"));

    testWarning(
        LINE_JOINER.join(
            "goog.module('example');",
            "/** @constructor */ function f() { this.prop; };",
            "f.prototype.method = function() { alert(this.prop); }"),
        CheckConformance.CONFORMANCE_VIOLATION,
        // TODO(tbreisacher): Can we display a more user-friendly name here?
        "Violation: My rule message\nThe property \"prop\" on type \"module$contents$example_f\"");
  }

  public void testCustomBanUnknownInterfaceProp1() {
    configuration =
        config(rule("BanUnknownTypedClassPropsReferences"), "My rule message", value("String"));

    String js =
        LINE_JOINER.join(
            "/** @interface */ function I() {}",
            "I.prototype.method = function() {};",
            "I.prototype.gak;",
            "/** @param {!I} a */",
            "function f(a) {",
            "  a.gak();",
            "}");

    this.mode = TypeInferenceMode.OTI_ONLY;
    testWarning(
        js,
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: My rule message\nThe property \"gak\" on type \"I\"");

    this.mode = TypeInferenceMode.NTI_ONLY;
    testWarning(
        js,
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: My rule message\nThe property \"gak\" on type \"I{gak:TOP_FUNCTION}\"");
  }

  public void testCustomBanUnknownInterfaceProp2() {
    configuration =
        config(rule("BanUnknownTypedClassPropsReferences"), "My rule message", value("String"));

    testSame(
        LINE_JOINER.join(
            "/** @interface */ function I() {}",
            "I.prototype.method = function() {};",
            "/** @param {I} a */ function f(a) {",
            "  a.method();",
            "}"));
  }

  public void testCustomBanGlobalVars1() {
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.ConformanceRules$BanGlobalVars'\n" +
        "  error_message: 'BanGlobalVars Message'\n" +
        "}";

    testWarning(
        "var x;",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: BanGlobalVars Message");

    testWarning(
        "function fn() {}",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: BanGlobalVars Message");

    testNoWarning("goog.provide('x');");

    // TODO(johnlenz): This might be overly conservative but doing otherwise is more complicated
    // so let see if we can get away with this.
    testWarning(
        "goog.provide('x'); var x;",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: BanGlobalVars Message");
  }

  public void testCustomBanGlobalVars2() {
    configuration =
        "requirement: {\n"
            + "  type: CUSTOM\n"
            + "  java_class: 'com.google.javascript.jscomp.ConformanceRules$BanGlobalVars'\n"
            + "  error_message: 'BanGlobalVars Message'\n"
            + "}";

    testSame(
        "goog.scope(function() {\n"
            + "  var x = {y: 'y'}\n"
            + "  var z = {\n"
            + "     [x.y]: 2\n"
            + "  }\n"
            + "});");
  }

  public void testRequireFileoverviewVisibility() {
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.ConformanceRules$" +
                       "RequireFileoverviewVisibility'\n" +
        "  error_message: 'RequireFileoverviewVisibility Message'\n" +
        "}";

    testWarning(
        "var foo = function() {};",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: RequireFileoverviewVisibility Message");

    testWarning(
        "/**\n" + "  * @fileoverview\n" + "  */\n" + "var foo = function() {};",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: RequireFileoverviewVisibility Message");

    testWarning(
        "/**\n" + "  * @package\n" + "  */\n" + "var foo = function() {};",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: RequireFileoverviewVisibility Message");

    testSame(
        "/**\n" +
        "  * @fileoverview\n" +
        "  * @package\n" +
        "  */\n" +
        "var foo = function() {};");
  }

  public void testNoImplicitlyPublicDecls() {
    configuration =
        "requirement: {\n" +
        "  type: CUSTOM\n" +
        "  java_class: 'com.google.javascript.jscomp.ConformanceRules$" +
                       "NoImplicitlyPublicDecls'\n" +
        "  error_message: 'NoImplicitlyPublicDecls Message'\n" +
        "}";

    testWarning(
        "goog.provide('foo.bar');\n" +
        "/** @constructor */foo.bar.Baz = function() {};",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: NoImplicitlyPublicDecls Message");
    testNoWarning(
        "/** @package\n@fileoverview */\n" +
        "goog.provide('foo.bar');\n" +
        "/** @constructor */foo.bar.Baz = function(){};");
    testNoWarning(
        "goog.provide('foo.bar');\n" +
        "/** @package @constructor */foo.bar.Baz = function(){};");

    testWarning(
        "goog.provide('foo.bar');\n" +
        "/** @public @constructor */foo.bar.Baz = function(){};\n" +
        "/** @type {number} */foo.bar.Baz.prototype.quux = 42;",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: NoImplicitlyPublicDecls Message");
    testNoWarning(
        "/** @fileoverview\n@package*/\n" +
        "goog.provide('foo.bar');\n" +
        "/** @public @constructor */foo.bar.Baz = function(){};\n" +
        "/** @type {number} */foo.bar.Baz.prototype.quux = 42;");
    testNoWarning(
        "goog.provide('foo.bar');\n" +
        "/** @public @constructor */foo.bar.Baz = function(){};\n" +
        "/** @package {number} */foo.bar.Baz.prototype.quux = 42;");

    testWarning(
        "goog.provide('foo');\n" +
        "/** @public @constructor */\n" +
        "foo.Bar = function() {\n" +
        "  /** @type {number} */ this.baz = 52;\n" +
        "};",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: NoImplicitlyPublicDecls Message");
    testNoWarning(
        "goog.provide('foo');\n" +
        "/** @public @constructor */\n" +
        "foo.Bar = function() {\n" +
        "  /** @package {number} */ this.baz = 52;\n" +
        "};");
    testNoWarning(
        "/** @fileoverview\n@package */\n" +
        "goog.provide('foo');\n" +
        "/** @constructor */\n" +
        "foo.Bar = function() {\n" +
        "  /** @type {number} */ this.baz = 52;\n" +
        "};");

    testNoWarning("goog.provide('foo.bar');");

    testNoWarning(
        "goog.provide('foo');\n" +
        "/** @public @constructor */" +
        "foo.Bar = function() {};\n" +
        "/** @public */foo.Bar.prototype.baz = function() {};\n" +
        "/** @public @constructor @extends {foo.Bar} */\n" +
        "foo.Quux = function() {};\n" +
        "/** @override */foo.Quux.prototype.baz = function() {};");

    // These kinds of declarations aren't currently caught by
    // NoImplicitlyPublicDecls, but they could be.
    testNoWarning("var foo");
    testNoWarning("var foo = 42;");
    testNoWarning("goog.provide('foo');\n" +
        "/** @constructor @public */foo.Bar = function() {};\n" +
        "foo.Bar.prototype = {\n" +
        "  baz: function(){}\n" +
        "};");
  }

  public void testCustomBanUnresolvedType() {
    configuration =
        "requirement: {\n"
        + "  type: CUSTOM\n"
        + "  java_class: 'com.google.javascript.jscomp.ConformanceRules$BanUnresolvedType'\n"
        + "  error_message: 'BanUnresolvedType Message'\n"
        + "}";

    // NOTE(aravindpg): In NTI we annotate the node `a` with its inferred type instead of Unknown,
    // and so this test doesn't recognize `a` as unresolved. Fixing this is undesirable.
    // However, we do intend to add warnings for unfulfilled forward declares, which essentially
    // addresses this use case.
    this.mode = TypeInferenceMode.OTI_ONLY;
    testWarning(
        "goog.forwardDeclare('Foo');" + "/** @param {Foo} a */ function f(a) {a.foo()};",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: BanUnresolvedType Message");

    this.mode = TypeInferenceMode.BOTH;
    testSame(LINE_JOINER.join(
        "/** @suppress {newCheckTypes}",
        " *  @param {!Object<string, ?>} data",
        " */",
        "function foo(data) {",
        "  data['bar'].baz();",
        "}"));
  }

  public void testMergeRequirements() {
    Compiler compiler = createCompiler();
    ConformanceConfig.Builder builder = ConformanceConfig.newBuilder();
    builder.addRequirementBuilder().setRuleId("a").addWhitelist("x").addWhitelistRegexp("m");
    builder.addRequirementBuilder().setExtends("a").addWhitelist("y").addWhitelistRegexp("n");
    List<Requirement> requirements =
        CheckConformance.mergeRequirements(compiler, ImmutableList.of(builder.build()));
    assertThat(requirements).hasSize(1);
    Requirement requirement = requirements.get(0);
    assertEquals(2, requirement.getWhitelistCount());
    assertEquals(2, requirement.getWhitelistRegexpCount());
  }

  public void testMergeRequirements_findsDuplicates() {
    Compiler compiler = createCompiler();
    ErrorManager errorManager = new BlackHoleErrorManager();
    compiler.setErrorManager(errorManager);
    ConformanceConfig.Builder builder = ConformanceConfig.newBuilder();
    builder.addRequirementBuilder().addWhitelist("x").addWhitelist("x");
    CheckConformance.mergeRequirements(compiler, ImmutableList.of(builder.build()));
    assertEquals(1, errorManager.getErrorCount());
  }

  public void testCustomBanNullDeref1() {
    configuration = config(rule("BanNullDeref"), "My rule message");

    String externs = EXTERNS + "String.prototype.prop;";

    testSame(
        externs,
        "/** @param {string|null} n */ function f(n) { alert(n.prop); }",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: My rule message");

    testSame(
        externs,
        "/** @param {string|null} n */ function f(n) { alert(n['prop']); }",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: My rule message");

    testSame(
        externs,
        "/** @param {string|null} n  @suppress {newCheckTypes} */"
        + "function f(n) { alert('prop' in n); }",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: My rule message");

    testSame(
        externs,
        "/** @param {string|undefined} n */ function f(n) { alert(n.prop); }",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: My rule message");

    testSame(
        externs,
        "/** @param {?Function} fnOrNull */ function f(fnOrNull) { fnOrNull(); }",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: My rule message");

    testSame(
        externs,
        "/** @param {?Function} fnOrNull */ function f(fnOrNull) { new fnOrNull(); }",
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: My rule message");

    testSame(
        externs,
        "/** @param {string} n */ function f(n) { alert(n.prop); }", null);

    testSame(
        externs,
        "/** @param {?} n */ function f(n) { alert(n.prop); }", null);
  }

  public void testCustomBanNullDeref2() {
    configuration =
        config(rule("BanNullDeref"), "My rule message");

    String externs = EXTERNS + "String.prototype.prop;";

    final String code = "/** @param {?String} n */ function f(n) { alert(n.prop); }";

    testSame(
        externs,
        code,
        CheckConformance.CONFORMANCE_VIOLATION,
        "Violation: My rule message");

    configuration =
        config(rule("BanNullDeref"), "My rule message", value("String"));

    testSame(externs, code, null);
  }

  public void testCustomBanNullDeref3() {
    configuration =
        config(rule("BanNullDeref"), "My rule message");

    // Test doesn't run without warnings in NTI because of the way it handles typedefs.
    final String typedefExterns = LINE_JOINER.join(
        EXTERNS,
        "/** @fileoverview @suppress {newCheckTypes} */",
        "/** @const */ var ns = {};",
        "/** @enum {number} */ ns.Type.State = {OPEN: 0};",
        "/** @typedef {{a:string}} */ ns.Type;",
        "");

    final String code = LINE_JOINER.join(
        "/** @suppress {newCheckTypes} @return {void} n */",
        "function f() { alert(ns.Type.State.OPEN); }");
    testSame(typedefExterns, code, null);
  }

  public void testCustomBanNullDeref4() {
    configuration =
        config(rule("BanNullDeref"), "My rule message");

    testSame(
        LINE_JOINER.join(
            "/** @suppress {newCheckTypes} @param {*} x */",
            "function f(x) {",
            "  return x.toString();",
            "}"));
  }

  public void testRequireUseStrict0() {
    configuration = config(rule("RequireUseStrict"), "My rule message");

    testWarning("anything;", CheckConformance.CONFORMANCE_VIOLATION, "Violation: My rule message");
  }

  public void testRequireUseStrict1() {
    configuration = config(rule("RequireUseStrict"), "My rule message");

    testSame("'use strict';");
  }

  public void testRequireUseStrict2() {
    configuration = config(rule("RequireUseStrict"), "My rule message");

    test("goog.module('foo');", "'use strict'; /** @const */ var module$exports$foo={};");
  }

  public void testRequireUseStrict3() {
    configuration = config(rule("RequireUseStrict"), "My rule message");

    test(
        "export var x = 2;",
        LINE_JOINER.join(
            "/**",
            " * @fileoverview",
            " * @suppress {missingProvide,missingRequire}",
            " */",
            "",
            "'use strict';",
            "/** @const */ var module$testcode = {};",
            "var x$$module$testcode=2;",
            "module$testcode.x = x$$module$testcode;"));
  }
}
