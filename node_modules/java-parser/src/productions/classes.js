import { tokenMatcher } from "chevrotain";

export function defineRules($, t) {
  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-ClassDeclaration
  $.RULE("classDeclaration", () => {
    // Spec Deviation: extracted common "{classModifier}" prefix
    //      extraction is safe because there are no other references to
    //      "normalClassDeclaration" and "enumDeclaration"
    $.MANY(() => {
      $.SUBRULE($.classModifier);
    });
    $.OR([
      { ALT: () => $.SUBRULE($.normalClassDeclaration) },
      { ALT: () => $.SUBRULE($.enumDeclaration) },
      { ALT: () => $.SUBRULE($.recordDeclaration) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-NormalClassDeclaration
  $.RULE("normalClassDeclaration", () => {
    // Spec Deviation: extracted common "{classModifier}" to "classDeclaration"
    $.CONSUME(t.Class);
    $.SUBRULE($.typeIdentifier);
    $.OPTION(() => {
      $.SUBRULE($.typeParameters);
    });
    $.OPTION2(() => {
      $.SUBRULE($.superclass);
    });
    $.OPTION3(() => {
      $.SUBRULE($.superinterfaces);
    });
    $.OPTION4(() => {
      $.SUBRULE($.classPermits);
    });
    $.SUBRULE($.classBody);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-ClassModifier
  $.RULE("classModifier", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.annotation) },
      { ALT: () => $.CONSUME(t.Public) },
      { ALT: () => $.CONSUME(t.Protected) },
      { ALT: () => $.CONSUME(t.Private) },
      { ALT: () => $.CONSUME(t.Abstract) },
      { ALT: () => $.CONSUME(t.Static) },
      { ALT: () => $.CONSUME(t.Final) },
      { ALT: () => $.CONSUME(t.Sealed) },
      { ALT: () => $.CONSUME(t.NonSealed) },
      { ALT: () => $.CONSUME(t.Strictfp) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-TypeParameters
  $.RULE("typeParameters", () => {
    $.CONSUME(t.Less);
    $.SUBRULE($.typeParameterList);
    $.CONSUME(t.Greater);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-TypeParameterList
  $.RULE("typeParameterList", () => {
    $.SUBRULE($.typeParameter);
    $.MANY(() => {
      $.CONSUME(t.Comma);
      $.SUBRULE2($.typeParameter);
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-ClassExtends
  $.RULE("superclass", () => {
    $.CONSUME(t.Extends);
    $.SUBRULE($.classType);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-ClassImplements
  $.RULE("superinterfaces", () => {
    $.CONSUME(t.Implements);
    $.SUBRULE($.interfaceTypeList);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-InterfaceTypeList
  $.RULE("interfaceTypeList", () => {
    $.SUBRULE($.interfaceType);
    $.MANY(() => {
      $.CONSUME(t.Comma);
      $.SUBRULE2($.interfaceType);
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/preview/specs/sealed-classes-jls.html
  $.RULE("classPermits", () => {
    $.CONSUME(t.Permits);
    $.SUBRULE($.typeName);
    $.MANY(() => {
      $.CONSUME(t.Comma);
      $.SUBRULE2($.typeName);
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-ClassBody
  $.RULE("classBody", () => {
    $.CONSUME(t.LCurly);
    $.MANY(() => {
      $.SUBRULE($.classBodyDeclaration);
    });
    $.CONSUME(t.RCurly);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-ClassBodyDeclaration
  $.RULE("classBodyDeclaration", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.classMemberDeclaration) },
      { ALT: () => $.SUBRULE($.instanceInitializer) },
      { ALT: () => $.SUBRULE($.staticInitializer) },
      { ALT: () => $.SUBRULE($.constructorDeclaration) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-ClassMemberDeclaration
  $.RULE("classMemberDeclaration", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.fieldDeclaration) },
      { ALT: () => $.SUBRULE($.methodDeclaration) },
      { ALT: () => $.SUBRULE($.classDeclaration) },
      { ALT: () => $.SUBRULE($.interfaceDeclaration) },
      { ALT: () => $.CONSUME(t.Semicolon) }
    ]);
  });

  // // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-FieldDeclaration
  $.RULE("fieldDeclaration", () => {
    $.MANY(() => {
      $.SUBRULE($.fieldModifier);
    });
    $.SUBRULE($.unannType);
    $.SUBRULE($.variableDeclaratorList);
    $.CONSUME(t.Semicolon);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-FieldModifier
  $.RULE("fieldModifier", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.annotation) },
      { ALT: () => $.CONSUME(t.Public) },
      { ALT: () => $.CONSUME(t.Protected) },
      { ALT: () => $.CONSUME(t.Private) },
      { ALT: () => $.CONSUME(t.Static) },
      { ALT: () => $.CONSUME(t.Final) },
      { ALT: () => $.CONSUME(t.Transient) },
      { ALT: () => $.CONSUME(t.Volatile) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-VariableDeclaratorList
  $.RULE("variableDeclaratorList", () => {
    $.SUBRULE($.variableDeclarator);
    $.MANY({
      // required to distinguish from patternList
      GATE: () => !tokenMatcher(this.LA(3).tokenType, t.Identifier),
      DEF: () => {
        $.CONSUME(t.Comma);
        $.SUBRULE2($.variableDeclarator);
      }
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-VariableDeclarator
  $.RULE("variableDeclarator", () => {
    $.SUBRULE($.variableDeclaratorId);
    $.OPTION(() => {
      $.CONSUME(t.Equals);
      $.SUBRULE($.variableInitializer);
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se21/html/jls-8.html#jls-VariableDeclaratorId
  $.RULE("variableDeclaratorId", () => {
    $.OR([
      {
        ALT: () => {
          $.CONSUME(t.Identifier);
          $.OPTION(() => {
            $.SUBRULE($.dims);
          });
        }
      },
      { ALT: () => $.CONSUME(t.Underscore) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-VariableInitializer
  $.RULE("variableInitializer", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.expression) },
      { ALT: () => $.SUBRULE($.arrayInitializer) }
    ]);
  });

  // // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-UnannType
  $.RULE("unannType", () => {
    $.OR([
      // Spec Deviation: The array type "dims" suffix was extracted to this rule
      // to avoid backtracking for performance reasons.
      { ALT: () => $.SUBRULE($.unannPrimitiveTypeWithOptionalDimsSuffix) },
      { ALT: () => $.SUBRULE($.unannReferenceType) }
    ]);
  });

  $.RULE("unannPrimitiveTypeWithOptionalDimsSuffix", () => {
    $.SUBRULE($.unannPrimitiveType);
    $.OPTION({
      GATE: () => this.BACKTRACK_LOOKAHEAD($.isDims),
      DEF: () => $.SUBRULE2($.dims)
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-UnannPrimitiveType
  $.RULE("unannPrimitiveType", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.numericType) },
      { ALT: () => $.CONSUME(t.Boolean) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-UnannReferenceType
  $.RULE("unannReferenceType", () => {
    $.SUBRULE($.unannClassOrInterfaceType);
    $.OPTION({
      GATE: () => this.BACKTRACK_LOOKAHEAD($.isDims),
      DEF: () => $.SUBRULE2($.dims)
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-UnannClassType
  $.RULE("unannClassOrInterfaceType", () => {
    // Spec Deviation: The spec says: "UnannClassType  | UnannInterfaceType" but "UnannInterfaceType"
    //                 is not mentioned in the parser because it is identical to "UnannClassType"
    //                 The distinction is **semantic** not syntactic.
    $.SUBRULE($.unannClassType);
  });

  $.RULE("unannClassType", () => {
    // Spec Deviation: Refactored left recursion and alternation to iterations
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
      $.OPTION2(() => {
        $.SUBRULE2($.typeArguments);
      });
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-UnannInterfaceType
  $.RULE("unannInterfaceType", () => {
    $.SUBRULE($.unannClassType);
  });

  $.RULE("unannTypeVariable", () => {
    // TODO: Semantic Check: This Identifier cannot be "var"
    // TODO: or define as token type?
    $.CONSUME(t.Identifier);
  });

  // // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-MethodDeclaration
  $.RULE("methodDeclaration", () => {
    $.MANY(() => {
      $.SUBRULE($.methodModifier);
    });
    $.SUBRULE($.methodHeader);
    $.SUBRULE($.methodBody);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-MethodModifier
  $.RULE("methodModifier", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.annotation) },
      { ALT: () => $.CONSUME(t.Public) },
      { ALT: () => $.CONSUME(t.Protected) },
      { ALT: () => $.CONSUME(t.Private) },
      { ALT: () => $.CONSUME(t.Abstract) },
      { ALT: () => $.CONSUME(t.Static) },
      { ALT: () => $.CONSUME(t.Final) },
      { ALT: () => $.CONSUME(t.Synchronized) },
      { ALT: () => $.CONSUME(t.Native) },
      { ALT: () => $.CONSUME(t.Strictfp) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-MethodHeader
  $.RULE("methodHeader", () => {
    // Spec Deviation: extracted common prefix from both alternatives
    $.OPTION(() => {
      $.SUBRULE($.typeParameters);
      $.MANY(() => {
        $.SUBRULE($.annotation);
      });
    });
    $.SUBRULE($.result);
    $.SUBRULE($.methodDeclarator);
    $.OPTION2(() => {
      $.SUBRULE($.throws);
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-Result
  $.RULE("result", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.unannType) },
      { ALT: () => $.CONSUME(t.Void) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se21/html/jls-8.html#jls-MethodDeclarator
  $.RULE("methodDeclarator", () => {
    $.CONSUME(t.Identifier);
    $.CONSUME(t.LBrace);
    $.OR([
      {
        ALT: () => {
          $.SUBRULE($.receiverParameter);
          $.OPTION(() => {
            $.CONSUME(t.Comma);
            $.SUBRULE($.formalParameterList);
          });
        }
      },
      { ALT: () => $.OPTION1(() => $.SUBRULE1($.formalParameterList)) }
    ]);
    $.CONSUME(t.RBrace);
    $.OPTION2(() => {
      $.SUBRULE($.dims);
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-ReceiverParameter
  $.RULE("receiverParameter", () => {
    $.MANY(() => {
      $.SUBRULE($.annotation);
    });
    $.SUBRULE($.unannType);
    $.OPTION(() => {
      $.CONSUME(t.Identifier);
      $.CONSUME(t.Dot);
    });
    $.CONSUME(t.This);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-FormalParameterList
  $.RULE("formalParameterList", () => {
    $.SUBRULE($.formalParameter);
    $.MANY(() => {
      $.CONSUME(t.Comma);
      $.SUBRULE2($.formalParameter);
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-FormalParameter
  $.RULE("formalParameter", () => {
    $.OR([
      // Spec Deviation: extracted to "variableParaRegularParameter"
      { ALT: () => $.SUBRULE($.variableParaRegularParameter) },
      { ALT: () => $.SUBRULE($.variableArityParameter) }
    ]);
  });

  // Spec Deviation: extracted from "formalParameter"
  $.RULE("variableParaRegularParameter", () => {
    $.MANY(() => {
      $.SUBRULE($.variableModifier);
    });
    $.SUBRULE($.unannType);
    $.SUBRULE($.variableDeclaratorId);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-VariableArityParameter
  $.RULE("variableArityParameter", () => {
    $.MANY(() => {
      $.SUBRULE($.variableModifier);
    });
    $.SUBRULE($.unannType);
    $.MANY2(() => {
      $.SUBRULE($.annotation);
    });
    $.CONSUME(t.DotDotDot);
    $.CONSUME(t.Identifier);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-VariableModifier
  $.RULE("variableModifier", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.annotation) },
      { ALT: () => $.CONSUME(t.Final) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-Throws
  $.RULE("throws", () => {
    $.CONSUME(t.Throws);
    $.SUBRULE($.exceptionTypeList);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-ExceptionTypeList
  $.RULE("exceptionTypeList", () => {
    $.SUBRULE($.exceptionType);
    $.MANY(() => {
      $.CONSUME(t.Comma);
      $.SUBRULE2($.exceptionType);
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-ExceptionType
  $.RULE("exceptionType", () => {
    // Spec Deviation: "typeVariable" alternative is missing because
    //                 it is contained in classType.
    $.SUBRULE($.classType);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-MethodBody
  $.RULE("methodBody", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.block) },
      { ALT: () => $.CONSUME(t.Semicolon) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-InstanceInitializer
  $.RULE("instanceInitializer", () => {
    $.SUBRULE($.block);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-StaticInitializer
  $.RULE("staticInitializer", () => {
    $.CONSUME(t.Static);
    $.SUBRULE($.block);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-ConstructorDeclaration
  $.RULE("constructorDeclaration", () => {
    $.MANY(() => {
      $.SUBRULE($.constructorModifier);
    });
    $.SUBRULE($.constructorDeclarator);
    $.OPTION(() => {
      $.SUBRULE($.throws);
    });
    $.SUBRULE($.constructorBody);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-ConstructorModifier
  $.RULE("constructorModifier", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.annotation) },
      { ALT: () => $.CONSUME(t.Public) },
      { ALT: () => $.CONSUME(t.Protected) },
      { ALT: () => $.CONSUME(t.Private) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-ConstructorDeclarator
  $.RULE("constructorDeclarator", () => {
    $.OPTION(() => {
      $.SUBRULE($.typeParameters);
    });
    $.SUBRULE($.simpleTypeName);
    $.CONSUME(t.LBrace);
    $.OR([
      {
        ALT: () => {
          $.SUBRULE($.receiverParameter);
          $.OPTION1(() => {
            $.CONSUME(t.Comma);
            $.SUBRULE($.formalParameterList);
          });
        }
      },
      { ALT: () => $.OPTION2(() => $.SUBRULE1($.formalParameterList)) }
    ]);
    $.CONSUME(t.RBrace);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-SimpleTypeName
  $.RULE("simpleTypeName", () => {
    // TODO: implement: Identifier but not var
    $.CONSUME(t.Identifier);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-ConstructorBody
  $.RULE("constructorBody", () => {
    $.CONSUME(t.LCurly);
    $.OPTION(() => {
      $.SUBRULE($.explicitConstructorInvocation);
    });
    $.OPTION2(() => {
      $.SUBRULE($.blockStatements);
    });
    $.CONSUME(t.RCurly);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-ExplicitConstructorInvocation
  $.RULE("explicitConstructorInvocation", () => {
    // Spec Deviation: split into two separate sub rules.
    $.OR([
      {
        ALT: () => $.SUBRULE($.unqualifiedExplicitConstructorInvocation)
      },
      { ALT: () => $.SUBRULE($.qualifiedExplicitConstructorInvocation) }
    ]);
  });

  $.RULE("unqualifiedExplicitConstructorInvocation", () => {
    $.OPTION(() => {
      $.SUBRULE($.typeArguments);
    });
    $.OR([
      {
        ALT: () => $.CONSUME(t.This)
      },
      {
        ALT: () => $.CONSUME(t.Super)
      }
    ]);
    $.CONSUME(t.LBrace);
    $.OPTION2(() => {
      $.SUBRULE($.argumentList);
    });
    $.CONSUME(t.RBrace);
    $.CONSUME(t.Semicolon);
  });

  $.RULE("qualifiedExplicitConstructorInvocation", () => {
    // Spec Deviation: According to the spec the prefix may be a "primary' as well,
    //                 however, most primary variants don't make sense here
    // TODO: discover which primary forms could be valid here
    //       and handle only those specific cases.
    //       It is best if we avoid referencing "primary" rule from
    //       outside the expressions rules as the expressions rules are not aligned
    //       to the spec style, so we want the smallest possible "external api"
    //       for the expressions rules.
    $.SUBRULE($.expressionName);
    $.CONSUME(t.Dot);
    $.OPTION(() => {
      $.SUBRULE($.typeArguments);
    });
    $.CONSUME(t.Super);
    $.CONSUME(t.LBrace);
    $.OPTION2(() => {
      $.SUBRULE($.argumentList);
    });
    $.CONSUME(t.RBrace);
    $.CONSUME(t.Semicolon);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-EnumDeclaration
  $.RULE("enumDeclaration", () => {
    $.MANY(() => {
      $.SUBRULE($.classModifier);
    });
    $.CONSUME(t.Enum);
    $.SUBRULE($.typeIdentifier);
    $.OPTION(() => {
      $.SUBRULE($.superinterfaces);
    });
    $.SUBRULE($.enumBody);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-EnumBody
  $.RULE("enumBody", () => {
    $.CONSUME(t.LCurly);
    $.OPTION(() => {
      $.SUBRULE($.enumConstantList);
    });
    $.OPTION2(() => {
      $.CONSUME(t.Comma);
    });
    $.OPTION3(() => {
      $.SUBRULE($.enumBodyDeclarations);
    });
    $.CONSUME(t.RCurly);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-EnumConstantList
  $.RULE("enumConstantList", () => {
    $.SUBRULE($.enumConstant);
    $.MANY({
      GATE: () => {
        const nextToken = $.LA(2);
        return (
          tokenMatcher(nextToken, t.Identifier) || tokenMatcher(nextToken, t.At)
        );
      },
      DEF: () => {
        $.CONSUME(t.Comma);
        $.SUBRULE2($.enumConstant);
      }
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-EnumConstant
  $.RULE("enumConstant", () => {
    $.MANY(() => {
      $.SUBRULE($.enumConstantModifier);
    });
    $.CONSUME(t.Identifier);
    $.OPTION(() => {
      $.CONSUME(t.LBrace);
      $.OPTION2(() => {
        $.SUBRULE($.argumentList);
      });
      $.CONSUME(t.RBrace);
    });
    $.OPTION3(() => {
      $.SUBRULE($.classBody);
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-EnumConstantModifier
  $.RULE("enumConstantModifier", () => {
    $.SUBRULE($.annotation);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-EnumBodyDeclarations
  $.RULE("enumBodyDeclarations", () => {
    $.CONSUME(t.Semicolon);
    $.MANY(() => {
      $.SUBRULE($.classBodyDeclaration);
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-RecordHeader
  $.RULE("recordDeclaration", () => {
    $.CONSUME(t.Record);
    $.SUBRULE($.typeIdentifier);
    $.OPTION(() => {
      $.SUBRULE($.typeParameters);
    });
    $.SUBRULE($.recordHeader);
    $.OPTION2(() => {
      $.SUBRULE($.superinterfaces);
    });
    $.SUBRULE($.recordBody);
  });

  $.RULE("recordHeader", () => {
    $.CONSUME(t.LBrace);
    $.OPTION(() => {
      $.SUBRULE($.recordComponentList);
    });
    $.CONSUME(t.RBrace);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-RecordComponentList
  $.RULE("recordComponentList", () => {
    $.SUBRULE($.recordComponent);
    $.MANY(() => {
      $.CONSUME(t.Comma);
      $.SUBRULE2($.recordComponent);
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-RecordComponent
  $.RULE("recordComponent", () => {
    // Spec Deviation: extracted common "{recordComponentModifier} unannType" prefix
    //      extraction is safe because there are no other references to
    //      "variableArityRecordComponent"
    $.MANY(() => {
      $.SUBRULE($.recordComponentModifier);
    });
    $.SUBRULE($.unannType);
    $.OR([
      { ALT: () => $.CONSUME(t.Identifier) },
      { ALT: () => $.SUBRULE($.variableArityRecordComponent) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-VariableArityRecordComponent
  // Spec Deviation: common "{recordComponentModifier} unannType" prefix was extracted in "recordComponent"
  $.RULE("variableArityRecordComponent", () => {
    $.MANY(() => {
      $.SUBRULE($.annotation);
    });
    $.CONSUME(t.DotDotDot);
    $.CONSUME(t.Identifier);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-RecordComponentModifier
  $.RULE("recordComponentModifier", () => {
    $.SUBRULE($.annotation);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-RecordBody
  $.RULE("recordBody", () => {
    $.CONSUME(t.LCurly);
    $.MANY(() => {
      $.SUBRULE($.recordBodyDeclaration);
    });
    $.CONSUME(t.RCurly);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-RecordBodyDeclaration
  $.RULE("recordBodyDeclaration", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.compactConstructorDeclaration) },
      { ALT: () => $.SUBRULE($.classBodyDeclaration) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-8.html#jls-CompactConstructorDeclaration
  $.RULE("compactConstructorDeclaration", () => {
    $.MANY(() => {
      $.SUBRULE($.constructorModifier);
    });
    $.SUBRULE($.simpleTypeName);
    $.SUBRULE($.constructorBody);
  });

  $.RULE("isDims", () => {
    $.MANY($.annotation);
    return (
      tokenMatcher(this.LA(1).tokenType, t.LSquare) &&
      tokenMatcher(this.LA(2).tokenType, t.RSquare)
    );
  });
}
