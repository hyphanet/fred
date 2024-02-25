import { CstParser, isRecognitionException } from "chevrotain";
import { LLStarLookaheadStrategy } from "chevrotain-allstar";
import { allTokens, tokens as t } from "./tokens.js";
import * as lexicalStructure from "./productions/lexical-structure.js";
import * as typesValuesVariables from "./productions/types-values-and-variables.js";
import * as names from "./productions/names.js";
import * as packagesModules from "./productions/packages-and-modules.js";
import * as classes from "./productions/classes.js";
import * as interfaces from "./productions/interfaces.js";
import * as arrays from "./productions/arrays.js";
import * as blocksStatements from "./productions/blocks-and-statements.js";
import * as expressions from "./productions/expressions.js";
import { getSkipValidations } from "./utils.js";
import { shouldNotFormat } from "./comments.js";

/**
 * This parser attempts to strongly align with the specs style at:
 * -  https://docs.oracle.com/javase/specs/jls/se11/html/jls-19.html
 *
 * Deviations from the spec will be marked.
 *
 * Note that deviations from the spec do not mean deviations from Java Grammar.
 * Rather it means an **equivalent** grammar which was written differently, e.g:
 * - LL(k) vs LR(K)
 * - Left Recursions vs Repetitions
 * - NonTerminals combined together or divided to sub-NonTerminals
 * - ...
 *
 * A special type of spec deviations are the "super grammar" kind.
 * This means that the parser has been defined in such a way that it accept a
 * **strict superset** of the inputs the official grammar accepts.
 *
 * This technique is used to simplify the parser when narrowing the set
 * of accepted inputs can more easily be done in a post parsing phase.
 *
 * TODO: document guide lines for using back tracking
 *
 */
export default class JavaParser extends CstParser {
  constructor() {
    super(allTokens, {
      lookaheadStrategy: new LLStarLookaheadStrategy({
        logging: getSkipValidations() ? () => {} : undefined
      }),
      nodeLocationTracking: "full",
      // traceInitPerf: 2,
      skipValidations: getSkipValidations()
    });

    const $ = this;

    this.mostEnclosiveCstNodeByStartOffset = {};
    this.mostEnclosiveCstNodeByEndOffset = {};

    // ---------------------
    // Productions from §3 (Lexical Structure)
    // ---------------------
    // TODO: move this rule to the correct file
    $.RULE("typeIdentifier", () => {
      // TODO: implement: Identifier but not var in the lexer
      $.CONSUME(t.Identifier);
    });

    // Include the productions from all "chapters".
    lexicalStructure.defineRules.call(this, $, t);
    typesValuesVariables.defineRules.call(this, $, t);
    names.defineRules.call(this, $, t);
    classes.defineRules.call(this, $, t);
    packagesModules.defineRules.call(this, $, t);
    interfaces.defineRules.call(this, $, t);
    arrays.defineRules.call(this, $, t);
    blocksStatements.defineRules.call(this, $, t);
    expressions.defineRules.call(this, $, t);

    this.firstForUnaryExpressionNotPlusMinus = [];
    this.performSelfAnalysis();
    this.firstForUnaryExpressionNotPlusMinus =
      expressions.computeFirstForUnaryExpressionNotPlusMinus.call(this);
  }

  cstPostNonTerminal(ruleCstResult, ruleName) {
    if (this.isBackTracking()) {
      return;
    }
    super.cstPostNonTerminal(ruleCstResult, ruleName);
    this.mostEnclosiveCstNodeByStartOffset[ruleCstResult.location.startOffset] =
      ruleCstResult;
    this.mostEnclosiveCstNodeByEndOffset[ruleCstResult.location.endOffset] =
      ruleCstResult;

    shouldNotFormat(ruleCstResult, this.onOffCommentPairs);
  }

  BACKTRACK_LOOKAHEAD(production, errValue = false) {
    return this.ACTION(() => {
      this.isBackTrackingStack.push(1);
      // TODO: "saveRecogState" does not handle the occurrence stack
      const orgState = this.saveRecogState();
      try {
        // hack to enable outputting non-CST values from grammar rules.
        const { ruleName, originalGrammarAction } = production;
        try {
          this.ruleInvocationStateUpdate(
            this.fullRuleNameToShort[ruleName],
            ruleName,
            this.subruleIdx
          );
          return originalGrammarAction.call(this);
        } catch (e) {
          return this.invokeRuleCatch(e, true, () => undefined);
        } finally {
          this.ruleFinallyStateUpdate();
        }
      } catch (e) {
        if (isRecognitionException(e)) {
          return errValue;
        }
        throw e;
      } finally {
        this.reloadRecogState(orgState);
        this.isBackTrackingStack.pop();
      }
    });
  }

  setOnOffCommentPairs(onOffCommentPairs) {
    this.onOffCommentPairs = onOffCommentPairs;
  }
}
