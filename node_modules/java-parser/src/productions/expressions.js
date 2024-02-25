import { tokenMatcher } from "chevrotain";
export function defineRules($, t) {
  $.RULE("expression", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.lambdaExpression) },
      { ALT: () => $.SUBRULE($.ternaryExpression) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-15.html#jls-LambdaExpression
  $.RULE("lambdaExpression", () => {
    $.SUBRULE($.lambdaParameters);
    $.CONSUME(t.Arrow);
    $.SUBRULE($.lambdaBody);
  });

  $.RULE("lambdaParameters", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.lambdaParametersWithBraces) },
      { ALT: () => $.CONSUME(t.Identifier) },
      { ALT: () => $.CONSUME(t.Underscore) }
    ]);
  });

  $.RULE("lambdaParametersWithBraces", () => {
    $.CONSUME(t.LBrace);
    $.OPTION(() => {
      $.SUBRULE($.lambdaParameterList);
    });
    $.CONSUME(t.RBrace);
  });

  $.RULE("lambdaParameterList", () => {
    $.OR([
      {
        GATE: () => {
          const nextTokType = this.LA(1).tokenType;
          const nextNextTokType = this.LA(2).tokenType;
          return (
            tokenMatcher(nextTokType, t.Identifier) &&
            (tokenMatcher(nextNextTokType, t.RBrace) ||
              tokenMatcher(nextNextTokType, t.Comma))
          );
        },
        ALT: () => $.SUBRULE($.inferredLambdaParameterList)
      },
      { ALT: () => $.SUBRULE($.explicitLambdaParameterList) }
    ]);
  });

  $.RULE("inferredLambdaParameterList", () => {
    $.CONSUME(t.Identifier);
    $.MANY(() => {
      $.CONSUME(t.Comma);
      $.CONSUME2(t.Identifier);
    });
  });

  $.RULE("explicitLambdaParameterList", () => {
    $.SUBRULE($.lambdaParameter);
    $.MANY(() => {
      $.CONSUME(t.Comma);
      $.SUBRULE2($.lambdaParameter);
    });
  });

  $.RULE("lambdaParameter", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.regularLambdaParameter) },
      { ALT: () => $.SUBRULE($.variableArityParameter) }
    ]);
  });

  $.RULE("regularLambdaParameter", () => {
    $.MANY(() => {
      $.SUBRULE($.variableModifier);
    });
    $.SUBRULE($.lambdaParameterType);
    $.SUBRULE($.variableDeclaratorId);
  });

  $.RULE("lambdaParameterType", () => {
    $.OR({
      DEF: [
        { ALT: () => $.SUBRULE($.unannType) },
        { ALT: () => $.CONSUME(t.Var) }
      ],
      IGNORE_AMBIGUITIES: true
    });
  });

  $.RULE("lambdaBody", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.expression) },
      { ALT: () => $.SUBRULE($.block) }
    ]);
  });

  $.RULE("ternaryExpression", () => {
    $.SUBRULE($.binaryExpression);
    $.OPTION(() => {
      $.CONSUME(t.QuestionMark);
      $.SUBRULE($.expression);
      $.CONSUME(t.Colon);
      // TODO: in the grammar this is limited to "lambdaExpression: or "conditionalExpression"
      $.SUBRULE2($.expression);
    });
  });

  $.RULE("binaryExpression", () => {
    $.SUBRULE($.unaryExpression);
    $.MANY(() => {
      $.OR({
        DEF: [
          {
            ALT: () => {
              $.CONSUME(t.Instanceof);
              $.OR1([
                { ALT: () => $.SUBRULE($.pattern) },
                { ALT: () => $.SUBRULE($.referenceType) }
              ]);
            }
          },
          {
            ALT: () => {
              $.CONSUME(t.AssignmentOperator);
              $.SUBRULE2($.expression);
            }
          },
          // This is an example of why Java does not have a well designed grammar
          // See: https://manas.tech/blog/2008/10/12/why-java-generics-dont-have-problems-with-right-shift-operator.html
          // TODO: ensure the LT/GT sequences have no whitespace between each other.
          {
            // TODO: this is a bug in Chevrotain lookahead calculation. the "BinaryOperator" token can match "Less" or "Greater"
            //   as well, but because it is a **token Category** Chevrotain does not understand it need to looks two tokens ahead.
            GATE: () =>
              tokenMatcher($.LA(2).tokenType, t.Less) ||
              tokenMatcher($.LA(2).tokenType, t.Greater),
            ALT: () => {
              $.OR2([
                {
                  GATE: () => $.LA(1).startOffset + 1 === $.LA(2).startOffset,
                  ALT: () => {
                    $.CONSUME(t.Less);
                    $.CONSUME2(t.Less);
                  }
                },
                {
                  GATE: () => $.LA(1).startOffset + 1 === $.LA(2).startOffset,
                  ALT: () => {
                    $.CONSUME(t.Greater);
                    $.CONSUME2(t.Greater);
                    $.OPTION({
                      GATE: () =>
                        $.LA(0).startOffset + 1 === $.LA(1).startOffset,
                      DEF: () => $.CONSUME3(t.Greater)
                    });
                  }
                }
              ]);
              $.SUBRULE2($.unaryExpression);
            }
          },
          {
            ALT: () => {
              $.CONSUME(t.BinaryOperator);
              $.SUBRULE3($.unaryExpression);
            }
          }
        ],
        IGNORE_AMBIGUITIES: true // the ambiguity between 1 and 4 options is resolved by the order (instanceOf is first)
      });
    });
  });

  $.RULE("unaryExpression", () => {
    $.MANY(() => {
      $.CONSUME(t.UnaryPrefixOperator);
    });
    $.SUBRULE($.primary);
    $.MANY2(() => {
      $.CONSUME(t.UnarySuffixOperator);
    });
  });

  $.RULE("unaryExpressionNotPlusMinus", () => {
    $.MANY(() => {
      $.CONSUME(t.UnaryPrefixOperatorNotPlusMinus);
    });
    $.SUBRULE($.primary);
    $.MANY2(() => {
      $.CONSUME(t.UnarySuffixOperator);
    });
  });

  $.RULE("primary", () => {
    $.SUBRULE($.primaryPrefix);
    $.MANY(() => {
      $.SUBRULE($.primarySuffix);
    });
  });

  $.RULE("primaryPrefix", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.literal) },
      { ALT: () => $.CONSUME(t.This) },
      { ALT: () => $.CONSUME(t.Void) },
      { ALT: () => $.SUBRULE($.unannPrimitiveTypeWithOptionalDimsSuffix) },
      { ALT: () => $.SUBRULE($.fqnOrRefType) },
      { ALT: () => $.SUBRULE($.castExpression) },
      { ALT: () => $.SUBRULE($.parenthesisExpression) },
      { ALT: () => $.SUBRULE($.newExpression) },
      { ALT: () => $.SUBRULE($.switchStatement) }
    ]);
  });

  $.RULE("primarySuffix", () => {
    $.OR([
      {
        ALT: () => {
          $.CONSUME(t.Dot);
          $.OR2([
            { ALT: () => $.CONSUME(t.This) },
            {
              ALT: () => $.SUBRULE($.unqualifiedClassInstanceCreationExpression)
            },
            {
              ALT: () => {
                $.OPTION(() => {
                  $.SUBRULE($.typeArguments);
                });
                $.CONSUME(t.Identifier);
              }
            },
            { ALT: () => $.SUBRULE($.templateArgument) }
          ]);
        }
      },
      { ALT: () => $.SUBRULE($.methodInvocationSuffix) },
      { ALT: () => $.SUBRULE($.classLiteralSuffix) },
      { ALT: () => $.SUBRULE($.arrayAccessSuffix) },
      { ALT: () => $.SUBRULE($.methodReferenceSuffix) }
    ]);
  });

  // See https://github.com/jhipster/prettier-java/pull/154 to understand
  // why fqnOrRefTypePart is split in two rules (First and Rest)
  $.RULE("fqnOrRefType", () => {
    $.SUBRULE($.fqnOrRefTypePartFirst);

    $.MANY({
      // avoids ambiguity with primary suffixes
      GATE: () => {
        const nextNextToken = $.LA(2);
        return !(
          tokenMatcher(nextNextToken, t.Class) ||
          tokenMatcher(nextNextToken, t.This) ||
          tokenMatcher(nextNextToken, t.New) ||
          tokenMatcher(nextNextToken, t.StringLiteral) ||
          tokenMatcher(nextNextToken, t.TextBlock) ||
          tokenMatcher(nextNextToken, t.StringTemplateBegin) ||
          tokenMatcher(nextNextToken, t.TextBlockTemplateBegin)
        );
      },
      DEF: () => {
        $.CONSUME(t.Dot);
        $.SUBRULE2($.fqnOrRefTypePartRest);
      }
    });

    // in case of an arrayType
    $.OPTION({
      // it is not enough to check only the opening "[", we must avoid conflict with
      // arrayAccessSuffix
      GATE: () =>
        tokenMatcher($.LA(1).tokenType, t.At) ||
        tokenMatcher($.LA(2).tokenType, t.RSquare),
      DEF: () => {
        $.SUBRULE($.dims);
      }
    });
  });

  // TODO: validation:
  //       1. "annotation" cannot be mixed with "methodTypeArguments" or "Super".
  //       2. "methodTypeArguments" cannot be mixed with "classTypeArguments" or "annotation".
  //       3. "Super" cannot be mixed with "classTypeArguments" or "annotation".
  //       4. At most one "Super" may be used.
  //       5. "Super" may be last or one before last (last may also be first if there is only a single part).
  $.RULE("fqnOrRefTypePartRest", () => {
    $.MANY(() => {
      $.SUBRULE($.annotation);
    });

    $.OPTION(() => $.SUBRULE2($.typeArguments));

    $.SUBRULE($.fqnOrRefTypePartCommon);
  });

  $.RULE("fqnOrRefTypePartCommon", () => {
    $.OR([
      { ALT: () => $.CONSUME(t.Identifier) },
      { ALT: () => $.CONSUME(t.Super) }
    ]);

    let isRefTypeInMethodRef = false;
    // Performance optimization, only perform this backtracking when a '<' is found
    // TODO: performance optimization evaluation: avoid doing this backtracking for every "<" encountered.
    //       we could do it once (using global state) per "fqnOrRefType"
    // We could do it only once for
    if (tokenMatcher($.LA(1).tokenType, t.Less)) {
      isRefTypeInMethodRef = this.BACKTRACK_LOOKAHEAD($.isRefTypeInMethodRef);
    }

    $.OPTION2({
      // unrestricted typeArguments here would create an ambiguity with "LessThan" operator
      // e.g: "var x = a < b;"
      // The "<" would be parsed as the beginning of a "typeArguments"
      // and we will get an error: "expecting '>' but found: ';'"
      GATE: () => isRefTypeInMethodRef,
      DEF: () => {
        $.SUBRULE3($.typeArguments);
      }
    });
  });

  $.RULE("fqnOrRefTypePartFirst", () => {
    $.MANY(() => {
      $.SUBRULE($.annotation);
    });

    $.SUBRULE($.fqnOrRefTypePartCommon);
  });

  $.RULE("parenthesisExpression", () => {
    $.CONSUME(t.LBrace);
    $.SUBRULE($.expression);
    $.CONSUME(t.RBrace);
  });

  $.RULE("castExpression", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.primitiveCastExpression) },
      { ALT: () => $.SUBRULE($.referenceTypeCastExpression) }
    ]);
  });

  $.RULE("primitiveCastExpression", () => {
    $.CONSUME(t.LBrace);
    $.SUBRULE($.primitiveType);
    $.CONSUME(t.RBrace);
    $.SUBRULE($.unaryExpression);
  });

  $.RULE("referenceTypeCastExpression", () => {
    $.CONSUME(t.LBrace);
    $.SUBRULE($.referenceType);
    $.MANY(() => {
      $.SUBRULE($.additionalBound);
    });
    $.CONSUME(t.RBrace);
    $.OR([
      { ALT: () => $.SUBRULE($.lambdaExpression) },
      { ALT: () => $.SUBRULE($.unaryExpressionNotPlusMinus) }
    ]);
  });

  $.RULE("newExpression", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.arrayCreationExpression) },
      { ALT: () => $.SUBRULE($.unqualifiedClassInstanceCreationExpression) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-15.html#jls-UnqualifiedClassInstanceCreationExpression
  $.RULE("unqualifiedClassInstanceCreationExpression", () => {
    $.CONSUME(t.New);
    $.OPTION(() => {
      $.SUBRULE($.typeArguments);
    });
    $.SUBRULE($.classOrInterfaceTypeToInstantiate);
    $.CONSUME(t.LBrace);
    $.OPTION2(() => {
      $.SUBRULE($.argumentList);
    });
    $.CONSUME(t.RBrace);
    $.OPTION3(() => {
      $.SUBRULE($.classBody);
    });
  });

  $.RULE("classOrInterfaceTypeToInstantiate", () => {
    $.MANY(() => {
      $.SUBRULE($.annotation);
    });
    $.CONSUME(t.Identifier);
    $.MANY2(() => {
      $.CONSUME(t.Dot);
      $.MANY3(() => {
        $.SUBRULE2($.annotation);
      });
      $.CONSUME2(t.Identifier);
    });
    $.OPTION(() => {
      $.SUBRULE($.typeArgumentsOrDiamond);
    });
  });

  $.RULE("typeArgumentsOrDiamond", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.diamond) },
      { ALT: () => $.SUBRULE($.typeArguments) }
    ]);
  });

  $.RULE("diamond", () => {
    $.CONSUME(t.Less);
    $.CONSUME(t.Greater);
  });

  $.RULE("methodInvocationSuffix", () => {
    $.CONSUME(t.LBrace);
    $.OPTION2(() => {
      $.SUBRULE($.argumentList);
    });
    $.CONSUME(t.RBrace);
  });

  $.RULE("argumentList", () => {
    $.SUBRULE($.expression);
    $.MANY(() => {
      $.CONSUME(t.Comma);
      $.SUBRULE2($.expression);
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-15.html#jls-15.10.1
  $.RULE("arrayCreationExpression", () => {
    $.CONSUME(t.New);
    $.OR([
      { ALT: () => $.SUBRULE($.primitiveType) },
      { ALT: () => $.SUBRULE($.classOrInterfaceType) }
    ]);

    $.OR2([
      { ALT: () => $.SUBRULE($.arrayCreationDefaultInitSuffix) },
      { ALT: () => $.SUBRULE($.arrayCreationExplicitInitSuffix) }
    ]);
  });

  $.RULE("arrayCreationDefaultInitSuffix", () => {
    $.SUBRULE($.dimExprs);
    $.OPTION(() => {
      $.SUBRULE($.dims);
    });
  });

  $.RULE("arrayCreationExplicitInitSuffix", () => {
    $.SUBRULE($.dims);
    $.SUBRULE($.arrayInitializer);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-15.html#jls-DimExprs
  $.RULE("dimExprs", () => {
    $.SUBRULE($.dimExpr);
    $.MANY({
      // The GATE is to distinguish DimExpr from Dims :
      // the only difference between these two is the presence of an expression in the DimExpr
      // Example: If the GATE is not present double[3][] won't be parsed as the parser will try to parse "[]"
      // as a dimExpr instead of a dims
      GATE: () => tokenMatcher($.LA(2).tokenType, t.RSquare) === false,
      DEF: () => $.SUBRULE2($.dimExpr)
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-15.html#jls-DimExpr
  $.RULE("dimExpr", () => {
    $.MANY(() => {
      $.SUBRULE($.annotation);
    });
    $.CONSUME(t.LSquare);
    $.SUBRULE($.expression);
    $.CONSUME(t.RSquare);
  });

  $.RULE("classLiteralSuffix", () => {
    $.MANY(() => {
      $.CONSUME(t.LSquare);
      $.CONSUME(t.RSquare);
    });
    $.CONSUME(t.Dot);
    $.CONSUME(t.Class);
  });

  $.RULE("arrayAccessSuffix", () => {
    $.CONSUME(t.LSquare);
    $.SUBRULE($.expression);
    $.CONSUME(t.RSquare);
  });

  $.RULE("methodReferenceSuffix", () => {
    $.CONSUME(t.ColonColon);
    $.OPTION(() => {
      $.SUBRULE($.typeArguments);
    });

    $.OR([
      { ALT: () => $.CONSUME(t.Identifier) },
      // TODO: a constructor method reference ("new") can only be used
      //   in specific contexts, but perhaps this verification is best left
      //   for a semantic analysis phase
      { ALT: () => $.CONSUME(t.New) }
    ]);
  });

  $.RULE("templateArgument", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.template) },
      { ALT: () => $.CONSUME(t.StringLiteral) },
      { ALT: () => $.CONSUME(t.TextBlock) }
    ]);
  });

  $.RULE("template", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.stringTemplate) },
      { ALT: () => $.SUBRULE($.textBlockTemplate) }
    ]);
  });

  $.RULE("stringTemplate", () => {
    $.CONSUME(t.StringTemplateBegin);
    $.SUBRULE($.embeddedExpression);
    $.MANY(() => {
      $.CONSUME(t.StringTemplateMid);
      $.SUBRULE1($.embeddedExpression);
    });
    $.CONSUME(t.StringTemplateEnd);
  });

  $.RULE("textBlockTemplate", () => {
    $.CONSUME(t.TextBlockTemplateBegin);
    $.SUBRULE($.embeddedExpression);
    $.MANY(() => {
      $.CONSUME(t.TextBlockTemplateMid);
      $.SUBRULE1($.embeddedExpression);
    });
    $.CONSUME(t.TextBlockTemplateEnd);
  });

  $.RULE("embeddedExpression", () => {
    $.OPTION(() => {
      $.SUBRULE($.expression);
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se21/html/jls-14.html#jls-Pattern
  $.RULE("pattern", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.typePattern) },
      { ALT: () => $.SUBRULE($.recordPattern) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se21/html/jls-14.html#jls-TypePattern
  $.RULE("typePattern", () => {
    $.SUBRULE($.localVariableDeclaration);
  });

  // https://docs.oracle.com/javase/specs/jls/se21/html/jls-14.html#jls-RecordPattern
  $.RULE("recordPattern", () => {
    $.SUBRULE($.referenceType);
    $.CONSUME(t.LBrace);
    $.OPTION(() => {
      $.SUBRULE($.componentPatternList);
    });
    $.CONSUME(t.RBrace);
  });

  // https://docs.oracle.com/javase/specs/jls/se21/html/jls-14.html#jls-PatternList
  $.RULE("componentPatternList", () => {
    $.SUBRULE($.componentPattern);
    $.MANY(() => {
      $.CONSUME(t.Comma);
      $.SUBRULE2($.componentPattern);
    });
  });

  $.RULE("componentPattern", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.pattern) },
      { ALT: () => $.SUBRULE($.unnamedPattern) }
    ]);
  });

  $.RULE("unnamedPattern", () => {
    $.CONSUME(t.Underscore);
  });

  // https://docs.oracle.com/javase/specs/jls/se21/html/jls-14.html#jls-Guard
  $.RULE("guard", () => {
    $.CONSUME(t.When);
    $.SUBRULE($.expression);
  });

  $.RULE("isRefTypeInMethodRef", () => {
    let result = undefined;
    $.SUBRULE($.typeArguments);

    // arrayType
    const hasDims = $.OPTION(() => {
      $.SUBRULE($.dims);
    });

    const firstTokTypeAfterTypeArgs = this.LA(1).tokenType;
    if (tokenMatcher(firstTokTypeAfterTypeArgs, t.ColonColon)) {
      result = true;
    }
    // we must be at the end of a "referenceType" if "dims" were encountered
    // So there is not point to check farther
    else if (hasDims) {
      result = false;
    }

    // in the middle of a "classReferenceType"
    $.OPTION2(() => {
      $.CONSUME(t.Dot);
      $.SUBRULE($.classOrInterfaceType);
    });

    if (result !== undefined) {
      return result;
    }

    const firstTokTypeAfterRefType = this.LA(1).tokenType;
    return tokenMatcher(firstTokTypeAfterRefType, t.ColonColon);
  });
}

export function computeFirstForUnaryExpressionNotPlusMinus() {
  const firstUnaryExpressionNotPlusMinus = this.computeContentAssist(
    "unaryExpressionNotPlusMinus",
    []
  );
  const nextTokTypes = firstUnaryExpressionNotPlusMinus.map(
    x => x.nextTokenType
  );
  // uniq
  return nextTokTypes.filter((v, i, a) => a.indexOf(v) === i);
}
