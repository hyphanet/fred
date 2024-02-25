import { Lexer } from "chevrotain";
import { allTokens } from "./tokens.js";
import { getSkipValidations } from "./utils.js";

export default new Lexer(allTokens, {
  ensureOptimizations: true,
  skipValidations: getSkipValidations()
});
