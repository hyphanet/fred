export function defineRules($, t) {
  // ---------------------
  // Productions from §4 (Types, Values, and Variables)
  // ---------------------

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-4.html#jls-PrimitiveType
  $.RULE("primitiveType", () => {
    $.MANY(() => {
      $.SUBRULE($.annotation);
    });
    $.OR([
      { ALT: () => $.SUBRULE($.numericType) },
      { ALT: () => $.CONSUME(t.Boolean) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-4.html#jls-NumericType
  $.RULE("numericType", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.integralType) },
      { ALT: () => $.SUBRULE($.floatingPointType) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-4.html#jls-IntegralType
  $.RULE("integralType", () => {
    $.OR([
      { ALT: () => $.CONSUME(t.Byte) },
      { ALT: () => $.CONSUME(t.Short) },
      { ALT: () => $.CONSUME(t.Int) },
      { ALT: () => $.CONSUME(t.Long) },
      { ALT: () => $.CONSUME(t.Char) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-4.html#jls-FloatingPointType
  $.RULE("floatingPointType", () => {
    $.OR([
      { ALT: () => $.CONSUME(t.Float) },
      { ALT: () => $.CONSUME(t.Double) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-4.html#jls-ReferenceType
  $.RULE("referenceType", () => {
    $.MANY(() => {
      // Spec Deviation: by extracting the common "annotation" prefix
      // we can avoid backtracking and thus improve performance.
      // Note that the annotation prefix is still present inside
      // "primitiveType" and "classOrInterfaceType"
      $.SUBRULE($.annotation);
    });
    // Spec Deviation: The array type "dims" suffix was extracted to this rule
    // to avoid backtracking for performance reasons.
    $.OR({
      DEF: [
        {
          ALT: () => {
            $.SUBRULE($.primitiveType);
            $.SUBRULE($.dims);
          }
        },
        {
          // Spec Deviation: "typeVariable" alternative is missing because
          //                 it is included in "classOrInterfaceType"
          ALT: () => {
            $.SUBRULE($.classOrInterfaceType);
            $.OPTION(() => {
              $.SUBRULE2($.dims);
            });
          }
        }
      ],
      IGNORE_AMBIGUITIES: true // annotation prefix was extracted to remove ambiguities
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-4.html#jls-ClassOrInterfaceType
  $.RULE("classOrInterfaceType", () => {
    // Spec Deviation: The spec says: "classType | interfaceType" but "interfaceType"
    //                 is not mentioned in the parser because it is identical to "classType"
    //                 The distinction is **semantic** not syntactic.
    $.SUBRULE($.classType);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-4.html#jls-ClassType
  $.RULE("classType", () => {
    // Spec Deviation: Refactored left recursion and alternation to iterations
    $.MANY(() => {
      $.SUBRULE($.annotation);
    });
    $.CONSUME(t.Identifier);
    $.OPTION(() => {
      $.SUBRULE($.typeArguments);
    });
    $.MANY2(() => {
      $.CONSUME(t.Dot);
      $.MANY3(() => {
        $.SUBRULE2($.annotation);
      });
      // TODO: Semantic Check: This Identifier cannot be "var"
      $.CONSUME2(t.Identifier);
      $.OPTION2(() => $.SUBRULE2($.typeArguments));
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-4.html#jls-InterfaceType
  $.RULE("interfaceType", () => {
    $.SUBRULE($.classType);
  });

  $.RULE("typeVariable", () => {
    $.MANY(() => {
      $.SUBRULE($.annotation);
    });
    // TODO: Semantic Check: This Identifier cannot be "var"
    $.CONSUME(t.Identifier);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-4.html#jls-Dims
  $.RULE("dims", () => {
    $.MANY(() => {
      $.SUBRULE($.annotation);
    });
    $.CONSUME(t.LSquare);
    $.CONSUME(t.RSquare);
    $.MANY2({
      GATE: () => $.BACKTRACK_LOOKAHEAD($.isDims),
      DEF: () => {
        $.MANY3(() => {
          $.SUBRULE2($.annotation);
        });
        $.CONSUME2(t.LSquare);
        $.CONSUME2(t.RSquare);
      }
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-4.html#jls-TypeParameter
  $.RULE("typeParameter", () => {
    $.MANY(() => {
      $.SUBRULE($.typeParameterModifier);
    });
    $.SUBRULE($.typeIdentifier);
    $.OPTION(() => {
      $.SUBRULE($.typeBound);
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-4.html#jls-TypeParameterModifier
  $.RULE("typeParameterModifier", () => {
    $.SUBRULE($.annotation);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-4.html#jls-TypeBound
  $.RULE("typeBound", () => {
    $.CONSUME(t.Extends);
    // Spec Deviation: The alternative with "TypeVariable" is not specified
    //      because it's syntax is included in "classOrInterfaceType"
    $.SUBRULE($.classOrInterfaceType);
    $.MANY2(() => {
      $.SUBRULE($.additionalBound);
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-4.html#jls-AdditionalBound
  $.RULE("additionalBound", () => {
    $.CONSUME(t.And);
    $.SUBRULE($.interfaceType);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-4.html#jls-TypeArguments
  $.RULE("typeArguments", () => {
    $.CONSUME(t.Less);
    $.SUBRULE($.typeArgumentList);
    $.CONSUME(t.Greater);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-4.html#jls-TypeArgumentList
  $.RULE("typeArgumentList", () => {
    $.SUBRULE($.typeArgument);
    $.MANY(() => {
      $.CONSUME(t.Comma);
      $.SUBRULE2($.typeArgument);
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-4.html#jls-TypeArgument
  $.RULE("typeArgument", () => {
    // TODO: performance: evaluate flipping the order of alternatives
    $.OR([
      { ALT: () => $.SUBRULE($.referenceType) },
      { ALT: () => $.SUBRULE($.wildcard) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-4.html#jls-Wildcard
  $.RULE("wildcard", () => {
    $.MANY(() => {
      $.SUBRULE($.annotation);
    });
    $.CONSUME(t.QuestionMark);
    $.OPTION(() => {
      $.SUBRULE($.wildcardBounds);
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-4.html#jls-WildcardBounds
  $.RULE("wildcardBounds", () => {
    // TODO: consider in-lining suffix into the alternatives to match the spec more strongly
    $.OR([
      { ALT: () => $.CONSUME(t.Extends) },
      { ALT: () => $.CONSUME(t.Super) }
    ]);
    $.SUBRULE($.referenceType);
  });
}
