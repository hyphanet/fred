import { tokenMatcher } from "chevrotain";

export function defineRules($, t) {
  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-10.html#jls-ArrayInitializer
  $.RULE("arrayInitializer", () => {
    $.CONSUME(t.LCurly);
    $.OPTION(() => {
      $.SUBRULE($.variableInitializerList);
    });
    $.OPTION2(() => {
      $.CONSUME(t.Comma);
    });
    $.CONSUME(t.RCurly);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-10.html#jls-VariableInitializerList
  $.RULE("variableInitializerList", () => {
    $.SUBRULE($.variableInitializer);
    $.MANY({
      // The optional last "Comma" of an "arrayInitializer"
      GATE: () => tokenMatcher(this.LA(2).tokenType, t.RCurly) === false,
      DEF: () => {
        $.CONSUME(t.Comma);
        $.SUBRULE2($.variableInitializer);
      }
    });
  });
}
