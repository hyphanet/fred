[![npm](https://img.shields.io/npm/v/java-parser.svg)](https://www.npmjs.com/package/java-parser)

# java-parser

A Java Parser implemented in JavaScript using the [Chevrotain Parsing ToolKit](https://github.com/Chevrotain/chevrotain).
It outputs a **C**oncrete **S**yntax **T**ree, rather than an **A**bstract **S**yntax **T**ree.

- [On the Difference between a CST and an AST](https://stackoverflow.com/questions/1888854/what-is-the-difference-between-an-abstract-syntax-tree-and-a-concrete-syntax-tre)

Currently the main focus of this project is to be used in implementing a prettier Java plugin.
But it could also be used as the basis for other Java related tools in the JavaScript ecosystem.

## Installation

```sh
npm install java-parser --save-dev
```

or

```sh
yarn add java-parser --dev
```

## Usage

### Parsing

```javascript
const { parse } = require("java-parser");
const javaText = `
public class HelloWorldExample{
  public static void main(String args[]){
    System.out.println("Hello World !");
  }
}
`;

const cst = parse(javaText);
// explore the CST
```

### Traversing the CST

See relevant [Chevrotain documentation on CST Traversal](http://chevrotain.io/docs/guide/concrete_syntax_tree.html#traversing).

```javascript
const {
  BaseJavaCstVisitor,
  BaseJavaCstVisitorWithDefaults
} = require("java-parser");

// Use "BaseJavaCstVisitor" if you need to implement all the visitor methods yourself.
class LambdaArrowsPositionCollector extends BaseJavaCstVisitorWithDefaults {
  constructor() {
    super();
    this.customResult = [];
    this.validateVisitor();
  }

  lambdaExpression(ctx) {
    // Collects all the starting offsets of lambda arrows in lambdas with short (no parenthesis)
    // single argument lists: e.g:
    // - n -> n*n (will be collected)
    // - (n) -> n*n (not collected)
    if (ctx.lambdaParameters[0].children.Identifier) {
      this.customResult.push(ctx.Arrow[0].startOffset);
    }
  }
}

const lambdaArrowsCollector = new LambdaArrowsPositionCollector();
// The CST result from the previous code snippet
lambdaArrowsCollector.visit(cst);
lambdaArrowsCollector.customResult.forEach(arrowOffset => {
  console.log(arrowOffset);
});
```
