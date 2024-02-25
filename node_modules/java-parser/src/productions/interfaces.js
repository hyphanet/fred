import { tokenMatcher } from "chevrotain";

export function defineRules($, t) {
  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-9.html#jls-InterfaceDeclaration
  $.RULE("interfaceDeclaration", () => {
    // Spec Deviation: extracted the common "interfaceModifier" prefix to avoid backtracking.
    $.MANY(() => {
      $.SUBRULE($.interfaceModifier);
    });

    $.OR([
      { ALT: () => $.SUBRULE($.normalInterfaceDeclaration) },
      { ALT: () => $.SUBRULE($.annotationTypeDeclaration) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-9.html#jls-NormalInterfaceDeclaration
  $.RULE("normalInterfaceDeclaration", () => {
    // Spec Deviation: The "interfaceModifier" prefix was extracted to the "interfaceDeclaration"
    $.CONSUME(t.Interface);
    $.SUBRULE($.typeIdentifier);
    $.OPTION(() => {
      $.SUBRULE($.typeParameters);
    });
    $.OPTION2(() => {
      $.SUBRULE($.extendsInterfaces);
    });
    $.OPTION3(() => {
      $.SUBRULE($.interfacePermits);
    });
    $.SUBRULE($.interfaceBody);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-9.html#jls-InterfaceModifier
  $.RULE("interfaceModifier", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.annotation) },
      { ALT: () => $.CONSUME(t.Public) },
      { ALT: () => $.CONSUME(t.Protected) },
      { ALT: () => $.CONSUME(t.Private) },
      { ALT: () => $.CONSUME(t.Abstract) },
      { ALT: () => $.CONSUME(t.Static) },
      { ALT: () => $.CONSUME(t.Sealed) },
      { ALT: () => $.CONSUME(t.NonSealed) },
      { ALT: () => $.CONSUME(t.Strictfp) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-9.html#jls-InterfaceExtends
  $.RULE("extendsInterfaces", () => {
    $.CONSUME(t.Extends);
    $.SUBRULE($.interfaceTypeList);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/preview/specs/sealed-classes-jls.html
  $.RULE("interfacePermits", () => {
    $.CONSUME(t.Permits);
    $.SUBRULE($.typeName);
    $.MANY(() => {
      $.CONSUME(t.Comma);
      $.SUBRULE2($.typeName);
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-9.html#jls-InterfaceBody
  $.RULE("interfaceBody", () => {
    $.CONSUME(t.LCurly);
    $.MANY(() => {
      $.SUBRULE($.interfaceMemberDeclaration);
    });
    $.CONSUME(t.RCurly);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-9.html#jls-InterfaceMemberDeclaration
  $.RULE("interfaceMemberDeclaration", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.constantDeclaration) },
      { ALT: () => $.SUBRULE($.interfaceMethodDeclaration) },
      { ALT: () => $.SUBRULE($.classDeclaration) },
      { ALT: () => $.SUBRULE($.interfaceDeclaration) },
      { ALT: () => $.CONSUME(t.Semicolon) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-9.html#jls-ConstantDeclaration
  $.RULE("constantDeclaration", () => {
    $.MANY(() => {
      $.SUBRULE($.constantModifier);
    });
    $.SUBRULE($.unannType);
    $.SUBRULE($.variableDeclaratorList);
    $.CONSUME(t.Semicolon);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-9.html#jls-ConstantModifier
  $.RULE("constantModifier", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.annotation) },
      { ALT: () => $.CONSUME(t.Public) },
      { ALT: () => $.CONSUME(t.Static) },
      { ALT: () => $.CONSUME(t.Final) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-9.html#jls-InterfaceMethodDeclaration
  $.RULE("interfaceMethodDeclaration", () => {
    $.MANY(() => {
      $.SUBRULE($.interfaceMethodModifier);
    });
    $.SUBRULE($.methodHeader);
    $.SUBRULE($.methodBody);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-9.html#jls-InterfaceMethodModifier
  $.RULE("interfaceMethodModifier", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.annotation) },
      { ALT: () => $.CONSUME(t.Public) },
      { ALT: () => $.CONSUME(t.Private) },
      { ALT: () => $.CONSUME(t.Abstract) },
      { ALT: () => $.CONSUME(t.Default) },
      { ALT: () => $.CONSUME(t.Static) },
      { ALT: () => $.CONSUME(t.Strictfp) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-9.html#jls-AnnotationTypeDeclaration
  $.RULE("annotationTypeDeclaration", () => {
    // Spec Deviation: The "interfaceModifier" prefix was extracted to the "interfaceDeclaration"
    $.CONSUME(t.At);
    $.CONSUME(t.Interface);
    $.SUBRULE($.typeIdentifier);
    $.SUBRULE($.annotationTypeBody);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-9.html#jls-AnnotationTypeBody
  $.RULE("annotationTypeBody", () => {
    $.CONSUME(t.LCurly);
    $.MANY(() => {
      $.SUBRULE($.annotationTypeMemberDeclaration);
    });
    $.CONSUME(t.RCurly);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-9.html#jls-InterfaceMemberDeclaration
  $.RULE("annotationTypeMemberDeclaration", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.annotationTypeElementDeclaration) },
      { ALT: () => $.SUBRULE($.constantDeclaration) },
      { ALT: () => $.SUBRULE($.classDeclaration) },
      { ALT: () => $.SUBRULE($.interfaceDeclaration) },
      { ALT: () => $.CONSUME(t.Semicolon) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-9.html#jls-AnnotationTypeElementDeclaration
  $.RULE("annotationTypeElementDeclaration", () => {
    $.MANY(() => {
      $.SUBRULE($.annotationTypeElementModifier);
    });
    $.SUBRULE($.unannType);
    $.CONSUME(t.Identifier);
    $.CONSUME(t.LBrace);
    $.CONSUME(t.RBrace);
    $.OPTION(() => {
      $.SUBRULE($.dims);
    });
    $.OPTION2(() => {
      $.SUBRULE($.defaultValue);
    });
    $.CONSUME(t.Semicolon);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-9.html#jls-AnnotationTypeElementModifier
  $.RULE("annotationTypeElementModifier", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.annotation) },
      { ALT: () => $.CONSUME(t.Public) },
      { ALT: () => $.CONSUME(t.Abstract) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-9.html#jls-DefaultValue
  $.RULE("defaultValue", () => {
    $.CONSUME(t.Default);
    $.SUBRULE($.elementValue);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-9.html#jls-Annotation
  $.RULE("annotation", () => {
    // Spec Deviation: The common prefix for all three annotation types was extracted to this rule.
    // This was done to avoid the use of backtracking for performance reasons.
    $.CONSUME(t.At);
    $.SUBRULE($.typeName);

    // If this optional grammar was not invoked we have a markerAnnotation
    // https://docs.oracle.com/javase/specs/jls/se16/html/jls-9.html#jls-MarkerAnnotation
    $.OPTION(() => {
      $.CONSUME(t.LBrace);
      $.OR({
        DEF: [
          // normal annotation - https://docs.oracle.com/javase/specs/jls/se16/html/jls-9.html#jls-NormalAnnotation
          { ALT: () => $.SUBRULE($.elementValuePairList) },
          // Single Element Annotation - https://docs.oracle.com/javase/specs/jls/se16/html/jls-9.html#jls-SingleElementAnnotation
          {
            ALT: () => $.SUBRULE($.elementValue)
          },
          {
            ALT: () => {
              /* empty normal annotation contents */
            }
          }
        ],
        IGNORE_AMBIGUITIES: true
      });
      $.CONSUME(t.RBrace);
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-9.html#jls-ElementValuePairList
  $.RULE("elementValuePairList", () => {
    $.SUBRULE($.elementValuePair);
    $.MANY(() => {
      $.CONSUME(t.Comma);
      $.SUBRULE2($.elementValuePair);
    });
  });

  $.RULE("elementValuePair", () => {
    $.CONSUME(t.Identifier);
    $.CONSUME(t.Equals);
    $.SUBRULE($.elementValue);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-9.html#jls-ElementValue
  $.RULE("elementValue", () => {
    $.OR([
      // Spec Deviation: "conditionalExpression" replaced with "expression"
      // Because we cannot differentiate between the two using fixed lookahead.
      { ALT: () => $.SUBRULE($.expression) },
      { ALT: () => $.SUBRULE($.elementValueArrayInitializer) },
      { ALT: () => $.SUBRULE($.annotation) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-9.html#jls-ElementValueArrayInitializer
  $.RULE("elementValueArrayInitializer", () => {
    $.CONSUME(t.LCurly);
    $.OPTION(() => {
      $.SUBRULE($.elementValueList);
    });
    $.OPTION2(() => {
      $.CONSUME(t.Comma);
    });
    $.CONSUME(t.RCurly);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-9.html#jls-ElementValueList
  $.RULE("elementValueList", () => {
    $.SUBRULE($.elementValue);
    $.MANY({
      GATE: () => tokenMatcher($.LA(2).tokenType, t.RCurly) === false,
      DEF: () => {
        $.CONSUME(t.Comma);
        $.SUBRULE2($.elementValue);
      }
    });
  });
}
