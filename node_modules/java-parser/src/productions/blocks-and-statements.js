import { tokenMatcher } from "chevrotain";

// Spec Deviation: The "*NoShortIf" variations were removed as the ambiguity of
//                 the dangling else is resolved by attaching an "else" block
//                 to the nearest "if"
export function defineRules($, t) {
  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-Block
  $.RULE("block", () => {
    $.CONSUME(t.LCurly);
    $.OPTION(() => {
      $.SUBRULE($.blockStatements);
    });
    $.CONSUME(t.RCurly);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-BlockStatements
  $.RULE("blockStatements", () => {
    $.SUBRULE($.blockStatement);
    $.MANY(() => {
      $.SUBRULE2($.blockStatement);
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-BlockStatement
  $.RULE("blockStatement", () => {
    $.OR({
      DEF: [
        { ALT: () => $.SUBRULE($.localVariableDeclarationStatement) },
        { ALT: () => $.SUBRULE($.classDeclaration) },
        { ALT: () => $.SUBRULE($.interfaceDeclaration) },
        { ALT: () => $.SUBRULE($.statement) }
      ],
      IGNORE_AMBIGUITIES: true
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-LocalVariableDeclarationStatement
  $.RULE("localVariableDeclarationStatement", () => {
    $.SUBRULE($.localVariableDeclaration);
    $.CONSUME(t.Semicolon);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-LocalVariableDeclaration
  $.RULE("localVariableDeclaration", () => {
    $.MANY(() => {
      $.SUBRULE($.variableModifier);
    });
    $.SUBRULE($.localVariableType);
    $.SUBRULE($.variableDeclaratorList);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-LocalVariableType
  $.RULE("localVariableType", () => {
    $.OR({
      DEF: [
        { ALT: () => $.SUBRULE($.unannType) },
        { ALT: () => $.CONSUME(t.Var) }
      ],
      IGNORE_AMBIGUITIES: true
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-Statement
  $.RULE("statement", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.statementWithoutTrailingSubstatement) },
      { ALT: () => $.SUBRULE($.labeledStatement) },
      // Spec deviation: combined "IfThenStatement" and "IfThenElseStatement"
      { ALT: () => $.SUBRULE($.ifStatement) },
      { ALT: () => $.SUBRULE($.whileStatement) },
      { ALT: () => $.SUBRULE($.forStatement) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-StatementWithoutTrailingSubstatement
  $.RULE("statementWithoutTrailingSubstatement", () => {
    $.OR({
      DEF: [
        { ALT: () => $.SUBRULE($.block) },
        { ALT: () => $.SUBRULE($.yieldStatement) },
        { ALT: () => $.SUBRULE($.emptyStatement) },
        {
          GATE: () => !tokenMatcher(this.LA(1).tokenType, t.Switch),
          ALT: () => $.SUBRULE($.expressionStatement)
        },
        { ALT: () => $.SUBRULE($.assertStatement) },
        { ALT: () => $.SUBRULE($.switchStatement) },
        { ALT: () => $.SUBRULE($.doStatement) },
        { ALT: () => $.SUBRULE($.breakStatement) },
        { ALT: () => $.SUBRULE($.continueStatement) },
        { ALT: () => $.SUBRULE($.returnStatement) },
        { ALT: () => $.SUBRULE($.synchronizedStatement) },
        { ALT: () => $.SUBRULE($.throwStatement) },
        { ALT: () => $.SUBRULE($.tryStatement) }
      ],
      IGNORE_AMBIGUITIES: true
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-EmptyStatement
  $.RULE("emptyStatement", () => {
    $.CONSUME(t.Semicolon);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-LabeledStatement
  $.RULE("labeledStatement", () => {
    $.CONSUME(t.Identifier);
    $.CONSUME(t.Colon);
    $.SUBRULE($.statement);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-ExpressionStatement
  $.RULE("expressionStatement", () => {
    $.SUBRULE($.statementExpression);
    $.CONSUME(t.Semicolon);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-StatementExpression
  $.RULE("statementExpression", () => {
    // Spec deviation: The many alternatives here were replaced with
    //                 the "expression" rule as it contains them all,
    //                 and distinguishing between the alternatives cannot be done
    //                 using a fixed lookahead.
    // TODO: verify the resulting expression is one of the valid alternatives?
    $.SUBRULE($.expression);
  });

  // Spec deviation: combined "IfThenStatement" and "IfThenElseStatement"
  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-IfThenStatement
  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-IfThenElseStatement
  $.RULE("ifStatement", () => {
    $.CONSUME(t.If);
    $.CONSUME(t.LBrace);
    $.SUBRULE($.expression);
    $.CONSUME(t.RBrace);
    $.SUBRULE($.statement);
    $.OPTION(() => {
      $.CONSUME(t.Else);
      $.SUBRULE2($.statement);
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-AssertStatement
  $.RULE("assertStatement", () => {
    $.CONSUME(t.Assert);
    $.SUBRULE($.expression);
    $.OPTION(() => {
      $.CONSUME(t.Colon);
      $.SUBRULE2($.expression);
    });
    $.CONSUME(t.Semicolon);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-SwitchStatement
  $.RULE("switchStatement", () => {
    $.CONSUME(t.Switch);
    $.CONSUME(t.LBrace);
    $.SUBRULE($.expression);
    $.CONSUME(t.RBrace);
    $.SUBRULE($.switchBlock);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-SwitchBlock
  $.RULE("switchBlock", () => {
    $.CONSUME(t.LCurly);
    $.OR([
      { ALT: () => $.MANY(() => $.SUBRULE($.switchBlockStatementGroup)) },
      { ALT: () => $.MANY2(() => $.SUBRULE($.switchRule)) }
    ]);
    $.CONSUME(t.RCurly);
  });

  $.RULE("switchBlockStatementGroup", () => {
    $.SUBRULE($.switchLabel);
    $.CONSUME(t.Colon);
    $.OPTION(() => {
      $.SUBRULE($.blockStatements);
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se21/html/jls-14.html#jls-SwitchLabel
  $.RULE("switchLabel", () => {
    $.OR([
      {
        ALT: () => {
          $.CONSUME(t.Case);
          $.OR2([
            {
              ALT: () => {
                $.CONSUME(t.Null);
                $.OPTION2(() => {
                  $.CONSUME3(t.Comma);
                  $.CONSUME(t.Default);
                });
              }
            },
            {
              ALT: () => {
                $.SUBRULE($.pattern);
                $.MANY(() => {
                  $.CONSUME(t.Comma);
                  $.SUBRULE2($.pattern);
                });
                $.OPTION(() => {
                  $.SUBRULE($.guard);
                });
              }
            },
            {
              GATE: () => !tokenMatcher($.LA(1).tokenType, t.Null),
              ALT: () => {
                $.SUBRULE($.caseConstant);
                $.MANY2(() => {
                  $.CONSUME2(t.Comma);
                  $.SUBRULE2($.caseConstant);
                });
              }
            }
          ]);
        }
      },
      { ALT: () => $.CONSUME2(t.Default) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-SwitchRule
  $.RULE("switchRule", () => {
    $.SUBRULE($.switchLabel);
    $.CONSUME(t.Arrow);
    $.OR([
      { ALT: () => $.SUBRULE($.throwStatement) },
      { ALT: () => $.SUBRULE($.block) },
      {
        ALT: () => {
          $.SUBRULE($.expression);
          $.CONSUME(t.Semicolon);
        }
      }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-EnumConstantName
  $.RULE("caseConstant", () => {
    $.SUBRULE($.ternaryExpression);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-WhileStatement
  $.RULE("whileStatement", () => {
    $.CONSUME(t.While);
    $.CONSUME(t.LBrace);
    $.SUBRULE($.expression);
    $.CONSUME(t.RBrace);
    $.SUBRULE($.statement);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-DoStatement
  $.RULE("doStatement", () => {
    $.CONSUME(t.Do);
    $.SUBRULE($.statement);
    $.CONSUME(t.While);
    $.CONSUME(t.LBrace);
    $.SUBRULE($.expression);
    $.CONSUME(t.RBrace);
    $.CONSUME(t.Semicolon);
  });
  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-ForStatement
  $.RULE("forStatement", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.basicForStatement) },
      { ALT: () => $.SUBRULE($.enhancedForStatement) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-BasicForStatement
  $.RULE("basicForStatement", () => {
    $.CONSUME(t.For);
    $.CONSUME(t.LBrace);
    $.OPTION(() => {
      $.SUBRULE($.forInit);
    });
    $.CONSUME(t.Semicolon);
    $.OPTION2(() => {
      $.SUBRULE($.expression);
    });
    $.CONSUME2(t.Semicolon);
    $.OPTION3(() => {
      $.SUBRULE($.forUpdate);
    });
    $.CONSUME(t.RBrace);
    $.SUBRULE($.statement);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-ForInit
  $.RULE("forInit", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.localVariableDeclaration) },
      { ALT: () => $.SUBRULE($.statementExpressionList) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-ForUpdate
  $.RULE("forUpdate", () => {
    $.SUBRULE($.statementExpressionList);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-StatementExpressionList
  $.RULE("statementExpressionList", () => {
    $.SUBRULE($.statementExpression);
    $.MANY(() => {
      $.CONSUME(t.Comma);
      $.SUBRULE2($.statementExpression);
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-EnhancedForStatement
  $.RULE("enhancedForStatement", () => {
    $.CONSUME(t.For);
    $.CONSUME(t.LBrace);
    $.MANY(() => {
      $.SUBRULE($.variableModifier);
    });
    $.SUBRULE($.localVariableType);
    $.SUBRULE($.variableDeclaratorId);
    $.CONSUME(t.Colon);
    $.SUBRULE($.expression);
    $.CONSUME(t.RBrace);
    $.SUBRULE($.statement);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-BreakStatement
  $.RULE("breakStatement", () => {
    $.CONSUME(t.Break);
    $.OPTION(() => {
      $.CONSUME(t.Identifier);
    });
    $.CONSUME(t.Semicolon);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-ContinueStatement
  $.RULE("continueStatement", () => {
    $.CONSUME(t.Continue);
    $.OPTION(() => {
      $.CONSUME(t.Identifier);
    });
    $.CONSUME(t.Semicolon);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-ReturnStatement
  $.RULE("returnStatement", () => {
    $.CONSUME(t.Return);
    $.OPTION(() => {
      $.SUBRULE($.expression);
    });
    $.CONSUME(t.Semicolon);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-ThrowStatement
  $.RULE("throwStatement", () => {
    $.CONSUME(t.Throw);
    $.SUBRULE($.expression);
    $.CONSUME(t.Semicolon);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-SynchronizedStatement
  $.RULE("synchronizedStatement", () => {
    $.CONSUME(t.Synchronized);
    $.CONSUME(t.LBrace);
    $.SUBRULE($.expression);
    $.CONSUME(t.RBrace);
    $.SUBRULE($.block);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-TryStatement
  $.RULE("tryStatement", () => {
    $.OR([
      {
        ALT: () => {
          $.CONSUME(t.Try);
          $.SUBRULE($.block);
          $.OR2([
            {
              ALT: () => {
                $.SUBRULE($.catches);
                $.OPTION(() => {
                  $.SUBRULE($.finally);
                });
              }
            },
            { ALT: () => $.SUBRULE2($.finally) }
          ]);
        }
      },
      { ALT: () => $.SUBRULE($.tryWithResourcesStatement) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-Catches
  $.RULE("catches", () => {
    $.SUBRULE($.catchClause);
    $.MANY(() => {
      $.SUBRULE2($.catchClause);
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-CatchClause
  $.RULE("catchClause", () => {
    $.CONSUME(t.Catch);
    $.CONSUME(t.LBrace);
    $.SUBRULE($.catchFormalParameter);
    $.CONSUME(t.RBrace);
    $.SUBRULE($.block);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-CatchFormalParameter
  $.RULE("catchFormalParameter", () => {
    $.MANY(() => {
      $.SUBRULE($.variableModifier);
    });
    $.SUBRULE($.catchType);
    $.SUBRULE($.variableDeclaratorId);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-CatchType
  $.RULE("catchType", () => {
    $.SUBRULE($.unannClassType);
    $.MANY(() => {
      $.CONSUME(t.Or);
      $.SUBRULE2($.classType);
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-Finally
  $.RULE("finally", () => {
    $.CONSUME(t.Finally);
    $.SUBRULE($.block);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-TryWithResourcesStatement
  $.RULE("tryWithResourcesStatement", () => {
    $.CONSUME(t.Try);
    $.SUBRULE($.resourceSpecification);
    $.SUBRULE($.block);
    $.OPTION(() => {
      $.SUBRULE($.catches);
    });
    $.OPTION2(() => {
      $.SUBRULE($.finally);
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-ResourceSpecification
  $.RULE("resourceSpecification", () => {
    $.CONSUME(t.LBrace);
    $.SUBRULE($.resourceList);
    $.OPTION(() => {
      $.CONSUME(t.Semicolon);
    });
    $.CONSUME(t.RBrace);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-ResourceList
  $.RULE("resourceList", () => {
    $.SUBRULE($.resource);
    $.MANY({
      GATE: () => tokenMatcher($.LA(2).tokenType, t.RBrace) === false,
      DEF: () => {
        $.CONSUME(t.Semicolon);
        $.SUBRULE2($.resource);
      }
    });
  });

  // https://docs.oracle.com/javase/specs/jls/se21/html/jls-14.html#jls-Resource
  $.RULE("resource", () => {
    $.OR([
      { ALT: () => $.SUBRULE($.localVariableDeclaration) },
      { ALT: () => $.SUBRULE($.variableAccess) }
    ]);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-YieldStatement
  $.RULE("yieldStatement", () => {
    $.CONSUME(t.Yield);
    $.SUBRULE($.expression);
    $.CONSUME(t.Semicolon);
  });

  // https://docs.oracle.com/javase/specs/jls/se16/html/jls-14.html#jls-VariableAccess
  $.RULE("variableAccess", () => {
    // Spec Deviation: both "expressionName" and "fieldAccess" can be parsed
    //                 by the "primary" rule
    // TODO: verify that the primary is a fieldAccess or an expressionName.
    $.SUBRULE($.primary);
  });
}
