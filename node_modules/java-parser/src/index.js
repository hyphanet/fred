import JavaLexer from "./lexer.js";
import JavaParser from "./parser.js";
import { attachComments, matchFormatterOffOnPairs } from "./comments.js";

const parser = new JavaParser();

export const BaseJavaCstVisitor = parser.getBaseCstVisitorConstructor();
export const BaseJavaCstVisitorWithDefaults =
  parser.getBaseCstVisitorConstructorWithDefaults();

export function lexAndParse(inputText, entryPoint = "compilationUnit") {
  // Lex
  const lexResult = JavaLexer.tokenize(inputText);

  if (lexResult.errors.length > 0) {
    const firstError = lexResult.errors[0];
    throw Error(
      "Sad sad panda, lexing errors detected in line: " +
        firstError.line +
        ", column: " +
        firstError.column +
        "!\n" +
        firstError.message
    );
  }

  const tokens = lexResult.tokens;
  parser.input = tokens;
  parser.mostEnclosiveCstNodeByStartOffset = {};
  parser.mostEnclosiveCstNodeByEndOffset = {};

  parser.setOnOffCommentPairs(
    matchFormatterOffOnPairs(lexResult.groups.comments)
  );

  // Automatic CST created when parsing
  const cst = parser[entryPoint]();

  if (parser.errors.length > 0) {
    const error = parser.errors[0];
    throw Error(
      "Sad sad panda, parsing errors detected in line: " +
        error.token.startLine +
        ", column: " +
        error.token.startColumn +
        "!\n" +
        error.message +
        "!\n\t->" +
        error.context.ruleStack.join("\n\t->")
    );
  }

  attachComments(
    tokens,
    lexResult.groups.comments,
    parser.mostEnclosiveCstNodeByStartOffset,
    parser.mostEnclosiveCstNodeByEndOffset
  );

  return { cst, tokens };
}

export function parse(inputText, entryPoint = "compilationUnit") {
  return lexAndParse(inputText, entryPoint).cst;
}

export default {
  lexAndParse,
  parse,
  BaseJavaCstVisitor,
  BaseJavaCstVisitorWithDefaults
};
