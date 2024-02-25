export function defineRules($, t) {
  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-3.html#jls-Literal
  $.RULE("literal", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.integerLiteral) },
      { ALT: () => $.SUBRULE($.floatingPointLiteral) },
      { ALT: () => $.SUBRULE($.booleanLiteral) },
      { ALT: () => $.CONSUME(t.CharLiteral) },
      { ALT: () => $.CONSUME(t.TextBlock) },
      { ALT: () => $.CONSUME(t.StringLiteral) },
      { ALT: () => $.CONSUME(t.Null) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-3.html#jls-IntegerLiteral
  $.RULE("integerLiteral", () => {
    $.OR([
      { ALT: () => $.CONSUME(t.DecimalLiteral) },
      { ALT: () => $.CONSUME(t.HexLiteral) },
      { ALT: () => $.CONSUME(t.OctalLiteral) },
      { ALT: () => $.CONSUME(t.BinaryLiteral) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-3.html#jls-FloatingPointLiteral
  $.RULE("floatingPointLiteral", () => {
    $.OR([
      { ALT: () => $.CONSUME(t.FloatLiteral) },
      { ALT: () => $.CONSUME(t.HexFloatLiteral) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-3.html#jls-BooleanLiteral
  $.RULE("booleanLiteral", () => {
    $.OR([{ ALT: () => $.CONSUME(t.True) }, { ALT: () => $.CONSUME(t.False) }]);
  });
}
