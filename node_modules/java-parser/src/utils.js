/**
 * Should Parser / Lexer Validations be skipped?
 *
 * By default (productive mode) the validations would be skipped to reduce parser initialization time.
 * But during development flows (e.g testing/CI) they should be enabled to detect possible issues.
 *
 * @returns {boolean}
 */
export function getSkipValidations() {
  return (
    (typeof process !== "undefined" && // (not every runtime has a global `process` object
      process.env &&
      process.env["prettier-java-development-mode"] === "enabled") === false
  );
}
