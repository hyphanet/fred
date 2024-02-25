import { tokenMatcher, EOF } from "chevrotain";

export function defineRules($, t) {
  /**
   * Spec Deviation: As OrdinaryCompilationUnit and UnnamedClassCompilationUnit
   * both can have multiple class or interface declarations, both were combined
   * in the ordinaryCompilationUnit rule
   *
   * https://docs.oracle.com/javase/specs/jls/se21/html/jls-7.html#jls-7.3
   * https://docs.oracle.com/javase/specs/jls/se21/preview/specs/unnamed-classes-instance-main-methods-jls.html
   */
  $.RULE("compilationUnit", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.ordinaryCompilationUnit) },
      { ALT: () => $.SUBRULE($.modularCompilationUnit) }
    ]);
    // https://github.com/jhipster/prettier-java/pull/217
    $.CONSUME(EOF);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-7.html#jls-OrdinaryCompilationUnit
  $.RULE("ordinaryCompilationUnit", () => {
    $.OPTION(() => $.SUBRULE($.packageDeclaration));
    $.MANY(() => {
      $.SUBRULE3($.importDeclaration);
    });
    $.MANY2(() => {
      $.SUBRULE($.typeDeclaration);
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-7.html#jls-ModularCompilationUnit
  $.RULE("modularCompilationUnit", () => {
    $.MANY(() => {
      $.SUBRULE($.importDeclaration);
    });
    $.SUBRULE($.moduleDeclaration);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-7.html#jls-PackageDeclaration
  $.RULE("packageDeclaration", () => {
    $.MANY(() => {
      $.SUBRULE($.packageModifier);
    });
    $.CONSUME(t.Package);
    $.CONSUME(t.Identifier);
    $.MANY2(() => {
      $.CONSUME(t.Dot);
      $.CONSUME2(t.Identifier);
    });
    $.CONSUME2(t.Semicolon);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-7.html#jls-PackageModifier
  $.RULE("packageModifier", () => {
    $.SUBRULE($.annotation);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-7.html#jls-ImportDeclaration
  $.RULE("importDeclaration", () => {
    // Spec Deviation: The spec defines four different kinds of import declarations.
    //                 Our grammar however combines those into a single rule due to difficulties
    //                 distinguishing between the alternatives due to unbound common prefix.
    // TODO: A post parsing step is required to align with the official specs.
    //       The Identifier "var" is not allowed in all positions and variations of the importDeclaration
    $.OR([
      {
        ALT: () => {
          $.CONSUME(t.Import);
          $.OPTION(() => {
            $.CONSUME(t.Static);
          });
          $.SUBRULE($.packageOrTypeName);
          $.OPTION2(() => {
            $.CONSUME(t.Dot);
            $.CONSUME(t.Star);
          });
          $.CONSUME(t.Semicolon);
        }
      },
      // Spec Deviation: The spec do not allow empty statement in between imports.
      //                 However Java compiler consider empty statements valid, we chose
      //                 to support that case, thus deviate from the spec.
      //                 See here: https://github.com/jhipster/prettier-java/pull/158
      {
        ALT: () => $.SUBRULE($.emptyStatement)
      }
    ]);
  });

  /**
   * Spec Deviation: As OrdinaryCompilationUnit and UnnamedClassCompilationUnit
   * both can have multiple class or interface declarations, both were combined
   * in the ordinaryCompilationUnit rule
   *
   * As a result, the typeDeclaration combine TopLevelClassOrInterfaceDeclaration and includes fields and method declarations as well
   * to handle unnamed class compilation unit
   *
   * https://docs.oracle.com/javase/specs/jls/se21/html/jls-7.html#jls-TopLevelClassOrInterfaceDeclaration
   * https://docs.oracle.com/javase/specs/jls/se21/preview/specs/unnamed-classes-instance-main-methods-jls.html
   */
  $.RULE("typeDeclaration", () => {
    $.OR([
      { ALT: () => $.CONSUME(t.Semicolon) },
      { ALT: () => $.SUBRULE($.classDeclaration) },
      { ALT: () => $.SUBRULE($.interfaceDeclaration) },
      { ALT: () => $.SUBRULE($.fieldDeclaration) },
      { ALT: () => $.SUBRULE($.methodDeclaration) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-7.html#jls-ModuleDeclaration
  $.RULE("moduleDeclaration", () => {
    $.MANY(() => {
      $.SUBRULE($.annotation);
    });
    $.OPTION(() => {
      $.CONSUME(t.Open);
    });
    $.CONSUME(t.Module);
    $.CONSUME(t.Identifier);
    $.MANY2(() => {
      $.CONSUME(t.Dot);
      $.CONSUME2(t.Identifier);
    });
    $.CONSUME(t.LCurly);
    $.MANY3(() => {
      $.SUBRULE($.moduleDirective);
    });
    $.CONSUME(t.RCurly);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-7.html#jls-ModuleDirective
  $.RULE("moduleDirective", () => {
    // Spec Deviation: Each of the alternatives of "moduleDirective" was extracted
    //                 to its own nonTerminal, to reduce verbosity.
    $.OR([
      { ALT: () => $.SUBRULE($.requiresModuleDirective) },
      { ALT: () => $.SUBRULE($.exportsModuleDirective) },
      { ALT: () => $.SUBRULE($.opensModuleDirective) },
      { ALT: () => $.SUBRULE($.usesModuleDirective) },
      { ALT: () => $.SUBRULE($.providesModuleDirective) }
    ]);
  });

  $.RULE("requiresModuleDirective", () => {
    // Spec Deviation: extracted from "moduleDirective"
    $.CONSUME(t.Requires);
    $.MANY({
      GATE: () => {
        /**
         * https://docs.oracle.com/javase/specs/jls/se16/html/jls-3.html#jls-3.9 -
         *   There is one exception: immediately to the right of the character sequence `requires` in the ModuleDirective production,
         *   the character sequence `transitive` is tokenized as a keyword unless it is followed by a separator,
         *   in which case it is tokenized as an identifier.
         */
        return (
          (tokenMatcher($.LA(1).tokenType, t.Transitive) &&
            tokenMatcher($.LA(2).tokenType, t.Separators)) === false
        );
      },
      DEF: () => {
        $.SUBRULE($.requiresModifier);
      }
    });
    $.SUBRULE($.moduleName);
    $.CONSUME(t.Semicolon);
  });

  $.RULE("exportsModuleDirective", () => {
    // Spec Deviation: extracted from "moduleDirective"
    $.CONSUME(t.Exports);
    $.SUBRULE($.packageName);
    $.OPTION(() => {
      $.CONSUME(t.To);
      $.SUBRULE($.moduleName);
      $.MANY(() => {
        $.CONSUME(t.Comma);
        $.SUBRULE2($.moduleName);
      });
    });
    $.CONSUME(t.Semicolon);
  });

  $.RULE("opensModuleDirective", () => {
    // Spec Deviation: extracted from "moduleDirective"
    $.CONSUME(t.Opens);
    $.SUBRULE($.packageName);
    $.OPTION(() => {
      $.CONSUME(t.To);
      $.SUBRULE($.moduleName);
      $.MANY(() => {
        $.CONSUME(t.Comma);
        $.SUBRULE2($.moduleName);
      });
    });
    $.CONSUME(t.Semicolon);
  });

  $.RULE("usesModuleDirective", () => {
    // Spec Deviation: extracted from "moduleDirective"
    $.CONSUME(t.Uses);
    $.SUBRULE($.typeName);
    $.CONSUME(t.Semicolon);
  });

  $.RULE("providesModuleDirective", () => {
    // Spec Deviation: extracted from "moduleDirective"
    $.CONSUME(t.Provides);
    $.SUBRULE($.typeName);
    $.CONSUME(t.With);
    $.SUBRULE2($.typeName);
    $.MANY(() => {
      $.CONSUME(t.Comma);
      $.SUBRULE3($.typeName);
    });
    $.CONSUME(t.Semicolon);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-7.html#jls-RequiresModifier
  $.RULE("requiresModifier", () => {
    $.OR([
      { ALT: () => $.CONSUME(t.Transitive) },
      { ALT: () => $.CONSUME(t.Static) }
    ]);
  });
}
