import { tokenMatcher } from "chevrotain";
export function defineRules($, t) {
  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-6.html#jls-ModuleName
  $.RULE("moduleName", () => {
    $.CONSUME(t.Identifier);
    $.MANY(() => {
      $.CONSUME(t.Dot);
      $.CONSUME2(t.Identifier);
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-6.html#jls-PackageName
  $.RULE("packageName", () => {
    $.CONSUME(t.Identifier);
    $.MANY(() => {
      $.CONSUME(t.Dot);
      $.CONSUME2(t.Identifier);
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-6.html#jls-TypeName
  $.RULE("typeName", () => {
    // Spec Deviation: The last Identifier in a "typeName" may not be be "var"
    //                 But the parser does not check for that.
    // TODO: post parsing semantic check: last "Identifier" in a "typeName"
    //                                    cannot be the "var" keyword
    // TODO: option 2 implement "Not Var" Ident using token categories?
    $.CONSUME(t.Identifier);
    $.MANY(() => {
      $.CONSUME(t.Dot);
      $.CONSUME2(t.Identifier);
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-6.html#jls-ExpressionName
  $.RULE("expressionName", () => {
    // Spec Deviation: in-lined "ambiguousName" to be LL(K)
    $.CONSUME(t.Identifier);
    $.MANY({
      // expressionName could be called by "qualifiedExplicitConstructorInvocation"
      // in that case it may be followed by ".super" so we need to look two tokens
      // ahead.
      GATE: () => tokenMatcher(this.LA(2).tokenType, t.Identifier),
      DEF: () => {
        $.CONSUME(t.Dot);
        $.CONSUME2(t.Identifier);
      }
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-6.html#jls-MethodName
  $.RULE("methodName", () => {
    $.CONSUME(t.Identifier);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-6.html#jls-PackageOrTypeName
  $.RULE("packageOrTypeName", () => {
    $.CONSUME(t.Identifier);
    $.MANY({
      // In some contexts a "Dot Star" (.*) may appear
      // after a "packageOrTypeName", by default Chevrotain will
      // only look a single token ahead (Dot) to determine if another iteration
      // exists which will cause a parsing error for inputs such as:
      // "import a.b.c.*"
      GATE: () => tokenMatcher(this.LA(2).tokenType, t.Star) === false,
      DEF: () => {
        $.CONSUME(t.Dot);
        $.CONSUME2(t.Identifier);
      }
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-6.html#jls-AmbiguousName
  $.RULE("ambiguousName", () => {
    $.CONSUME(t.Identifier);
    $.MANY(() => {
      $.CONSUME(t.Dot);
      $.CONSUME2(t.Identifier);
    });
  });
}
