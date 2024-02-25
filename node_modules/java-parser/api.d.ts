import {
  CstNode as ChevrotainCstNode,
  CstNodeLocation,
  ICstVisitor,
  IToken as ChevrotainIToken
} from "chevrotain";

export interface CstNode extends ChevrotainCstNode {
  children: CstChildrenDictionary;
  leadingComments?: IToken[];
  trailingComments?: IToken[];
  ignore?: boolean;
  location: CstNodeLocation;
}

export interface IToken extends ChevrotainIToken {
  leadingComments?: IToken[];
  trailingComments?: IToken[];
  startOffset: number;
  startLine: number;
  startColumn: number;
  endOffset: number;
  endLine: number;
  endColumn: number;
}

export type CstElement = IToken | CstNode;

export declare type CstChildrenDictionary = {
  [identifier: string]: CstElement[];
};

export function lexAndParse(
  text: string,
  startProduction?: string
): {
  tokens: IToken[];
  cst: CstNode;
};

export function parse(text: string, startProduction?: string): CstNode;

export const BaseJavaCstVisitor: JavaCstVisitorConstructor<any, any>;
export const BaseJavaCstVisitorWithDefaults: JavaCstVisitorWithDefaultsConstructor<
  any,
  any
>;

export abstract class JavaCstVisitor<IN, OUT> implements ICstVisitor<IN, OUT> {
  // No need to implement these two methods
  // Generic Visit method implemented by the Chevrotain Library
  visit(cstNode: CstNode | CstNode[], param?: IN): OUT;
  validateVisitor(): void;

  typeIdentifier(ctx: TypeIdentifierCtx, param?: IN): OUT;
  literal(ctx: LiteralCtx, param?: IN): OUT;
  integerLiteral(ctx: IntegerLiteralCtx, param?: IN): OUT;
  floatingPointLiteral(ctx: FloatingPointLiteralCtx, param?: IN): OUT;
  booleanLiteral(ctx: BooleanLiteralCtx, param?: IN): OUT;
  primitiveType(ctx: PrimitiveTypeCtx, param?: IN): OUT;
  numericType(ctx: NumericTypeCtx, param?: IN): OUT;
  integralType(ctx: IntegralTypeCtx, param?: IN): OUT;
  floatingPointType(ctx: FloatingPointTypeCtx, param?: IN): OUT;
  referenceType(ctx: ReferenceTypeCtx, param?: IN): OUT;
  classOrInterfaceType(ctx: ClassOrInterfaceTypeCtx, param?: IN): OUT;
  classType(ctx: ClassTypeCtx, param?: IN): OUT;
  interfaceType(ctx: InterfaceTypeCtx, param?: IN): OUT;
  typeVariable(ctx: TypeVariableCtx, param?: IN): OUT;
  dims(ctx: DimsCtx, param?: IN): OUT;
  typeParameter(ctx: TypeParameterCtx, param?: IN): OUT;
  typeParameterModifier(ctx: TypeParameterModifierCtx, param?: IN): OUT;
  typeBound(ctx: TypeBoundCtx, param?: IN): OUT;
  additionalBound(ctx: AdditionalBoundCtx, param?: IN): OUT;
  typeArguments(ctx: TypeArgumentsCtx, param?: IN): OUT;
  typeArgumentList(ctx: TypeArgumentListCtx, param?: IN): OUT;
  typeArgument(ctx: TypeArgumentCtx, param?: IN): OUT;
  wildcard(ctx: WildcardCtx, param?: IN): OUT;
  wildcardBounds(ctx: WildcardBoundsCtx, param?: IN): OUT;
  moduleName(ctx: ModuleNameCtx, param?: IN): OUT;
  packageName(ctx: PackageNameCtx, param?: IN): OUT;
  typeName(ctx: TypeNameCtx, param?: IN): OUT;
  expressionName(ctx: ExpressionNameCtx, param?: IN): OUT;
  methodName(ctx: MethodNameCtx, param?: IN): OUT;
  packageOrTypeName(ctx: PackageOrTypeNameCtx, param?: IN): OUT;
  ambiguousName(ctx: AmbiguousNameCtx, param?: IN): OUT;
  classDeclaration(ctx: ClassDeclarationCtx, param?: IN): OUT;
  normalClassDeclaration(ctx: NormalClassDeclarationCtx, param?: IN): OUT;
  classModifier(ctx: ClassModifierCtx, param?: IN): OUT;
  typeParameters(ctx: TypeParametersCtx, param?: IN): OUT;
  typeParameterList(ctx: TypeParameterListCtx, param?: IN): OUT;
  superclass(ctx: SuperclassCtx, param?: IN): OUT;
  superinterfaces(ctx: SuperinterfacesCtx, param?: IN): OUT;
  interfaceTypeList(ctx: InterfaceTypeListCtx, param?: IN): OUT;
  classPermits(ctx: ClassPermitsCtx, param?: IN): OUT;
  classBody(ctx: ClassBodyCtx, param?: IN): OUT;
  classBodyDeclaration(ctx: ClassBodyDeclarationCtx, param?: IN): OUT;
  classMemberDeclaration(ctx: ClassMemberDeclarationCtx, param?: IN): OUT;
  fieldDeclaration(ctx: FieldDeclarationCtx, param?: IN): OUT;
  fieldModifier(ctx: FieldModifierCtx, param?: IN): OUT;
  variableDeclaratorList(ctx: VariableDeclaratorListCtx, param?: IN): OUT;
  variableDeclarator(ctx: VariableDeclaratorCtx, param?: IN): OUT;
  variableDeclaratorId(ctx: VariableDeclaratorIdCtx, param?: IN): OUT;
  variableInitializer(ctx: VariableInitializerCtx, param?: IN): OUT;
  unannType(ctx: UnannTypeCtx, param?: IN): OUT;
  unannPrimitiveTypeWithOptionalDimsSuffix(
    ctx: UnannPrimitiveTypeWithOptionalDimsSuffixCtx,
    param?: IN
  ): OUT;
  unannPrimitiveType(ctx: UnannPrimitiveTypeCtx, param?: IN): OUT;
  unannReferenceType(ctx: UnannReferenceTypeCtx, param?: IN): OUT;
  unannClassOrInterfaceType(ctx: UnannClassOrInterfaceTypeCtx, param?: IN): OUT;
  unannClassType(ctx: UnannClassTypeCtx, param?: IN): OUT;
  unannInterfaceType(ctx: UnannInterfaceTypeCtx, param?: IN): OUT;
  unannTypeVariable(ctx: UnannTypeVariableCtx, param?: IN): OUT;
  methodDeclaration(ctx: MethodDeclarationCtx, param?: IN): OUT;
  methodModifier(ctx: MethodModifierCtx, param?: IN): OUT;
  methodHeader(ctx: MethodHeaderCtx, param?: IN): OUT;
  result(ctx: ResultCtx, param?: IN): OUT;
  methodDeclarator(ctx: MethodDeclaratorCtx, param?: IN): OUT;
  receiverParameter(ctx: ReceiverParameterCtx, param?: IN): OUT;
  formalParameterList(ctx: FormalParameterListCtx, param?: IN): OUT;
  formalParameter(ctx: FormalParameterCtx, param?: IN): OUT;
  variableParaRegularParameter(
    ctx: VariableParaRegularParameterCtx,
    param?: IN
  ): OUT;
  variableArityParameter(ctx: VariableArityParameterCtx, param?: IN): OUT;
  variableModifier(ctx: VariableModifierCtx, param?: IN): OUT;
  throws(ctx: ThrowsCtx, param?: IN): OUT;
  exceptionTypeList(ctx: ExceptionTypeListCtx, param?: IN): OUT;
  exceptionType(ctx: ExceptionTypeCtx, param?: IN): OUT;
  methodBody(ctx: MethodBodyCtx, param?: IN): OUT;
  instanceInitializer(ctx: InstanceInitializerCtx, param?: IN): OUT;
  staticInitializer(ctx: StaticInitializerCtx, param?: IN): OUT;
  constructorDeclaration(ctx: ConstructorDeclarationCtx, param?: IN): OUT;
  constructorModifier(ctx: ConstructorModifierCtx, param?: IN): OUT;
  constructorDeclarator(ctx: ConstructorDeclaratorCtx, param?: IN): OUT;
  simpleTypeName(ctx: SimpleTypeNameCtx, param?: IN): OUT;
  constructorBody(ctx: ConstructorBodyCtx, param?: IN): OUT;
  explicitConstructorInvocation(
    ctx: ExplicitConstructorInvocationCtx,
    param?: IN
  ): OUT;
  unqualifiedExplicitConstructorInvocation(
    ctx: UnqualifiedExplicitConstructorInvocationCtx,
    param?: IN
  ): OUT;
  qualifiedExplicitConstructorInvocation(
    ctx: QualifiedExplicitConstructorInvocationCtx,
    param?: IN
  ): OUT;
  enumDeclaration(ctx: EnumDeclarationCtx, param?: IN): OUT;
  enumBody(ctx: EnumBodyCtx, param?: IN): OUT;
  enumConstantList(ctx: EnumConstantListCtx, param?: IN): OUT;
  enumConstant(ctx: EnumConstantCtx, param?: IN): OUT;
  enumConstantModifier(ctx: EnumConstantModifierCtx, param?: IN): OUT;
  enumBodyDeclarations(ctx: EnumBodyDeclarationsCtx, param?: IN): OUT;
  recordDeclaration(ctx: RecordDeclarationCtx, param?: IN): OUT;
  recordHeader(ctx: RecordHeaderCtx, param?: IN): OUT;
  recordComponentList(ctx: RecordComponentListCtx, param?: IN): OUT;
  recordComponent(ctx: RecordComponentCtx, param?: IN): OUT;
  variableArityRecordComponent(
    ctx: VariableArityRecordComponentCtx,
    param?: IN
  ): OUT;
  recordComponentModifier(ctx: RecordComponentModifierCtx, param?: IN): OUT;
  recordBody(ctx: RecordBodyCtx, param?: IN): OUT;
  recordBodyDeclaration(ctx: RecordBodyDeclarationCtx, param?: IN): OUT;
  compactConstructorDeclaration(
    ctx: CompactConstructorDeclarationCtx,
    param?: IN
  ): OUT;
  isDims(ctx: IsDimsCtx, param?: IN): OUT;
  compilationUnit(ctx: CompilationUnitCtx, param?: IN): OUT;
  ordinaryCompilationUnit(ctx: OrdinaryCompilationUnitCtx, param?: IN): OUT;
  modularCompilationUnit(ctx: ModularCompilationUnitCtx, param?: IN): OUT;
  packageDeclaration(ctx: PackageDeclarationCtx, param?: IN): OUT;
  packageModifier(ctx: PackageModifierCtx, param?: IN): OUT;
  importDeclaration(ctx: ImportDeclarationCtx, param?: IN): OUT;
  typeDeclaration(ctx: TypeDeclarationCtx, param?: IN): OUT;
  moduleDeclaration(ctx: ModuleDeclarationCtx, param?: IN): OUT;
  moduleDirective(ctx: ModuleDirectiveCtx, param?: IN): OUT;
  requiresModuleDirective(ctx: RequiresModuleDirectiveCtx, param?: IN): OUT;
  exportsModuleDirective(ctx: ExportsModuleDirectiveCtx, param?: IN): OUT;
  opensModuleDirective(ctx: OpensModuleDirectiveCtx, param?: IN): OUT;
  usesModuleDirective(ctx: UsesModuleDirectiveCtx, param?: IN): OUT;
  providesModuleDirective(ctx: ProvidesModuleDirectiveCtx, param?: IN): OUT;
  requiresModifier(ctx: RequiresModifierCtx, param?: IN): OUT;
  interfaceDeclaration(ctx: InterfaceDeclarationCtx, param?: IN): OUT;
  normalInterfaceDeclaration(
    ctx: NormalInterfaceDeclarationCtx,
    param?: IN
  ): OUT;
  interfaceModifier(ctx: InterfaceModifierCtx, param?: IN): OUT;
  extendsInterfaces(ctx: ExtendsInterfacesCtx, param?: IN): OUT;
  interfacePermits(ctx: InterfacePermitsCtx, param?: IN): OUT;
  interfaceBody(ctx: InterfaceBodyCtx, param?: IN): OUT;
  interfaceMemberDeclaration(
    ctx: InterfaceMemberDeclarationCtx,
    param?: IN
  ): OUT;
  constantDeclaration(ctx: ConstantDeclarationCtx, param?: IN): OUT;
  constantModifier(ctx: ConstantModifierCtx, param?: IN): OUT;
  interfaceMethodDeclaration(
    ctx: InterfaceMethodDeclarationCtx,
    param?: IN
  ): OUT;
  interfaceMethodModifier(ctx: InterfaceMethodModifierCtx, param?: IN): OUT;
  annotationTypeDeclaration(ctx: AnnotationTypeDeclarationCtx, param?: IN): OUT;
  annotationTypeBody(ctx: AnnotationTypeBodyCtx, param?: IN): OUT;
  annotationTypeMemberDeclaration(
    ctx: AnnotationTypeMemberDeclarationCtx,
    param?: IN
  ): OUT;
  annotationTypeElementDeclaration(
    ctx: AnnotationTypeElementDeclarationCtx,
    param?: IN
  ): OUT;
  annotationTypeElementModifier(
    ctx: AnnotationTypeElementModifierCtx,
    param?: IN
  ): OUT;
  defaultValue(ctx: DefaultValueCtx, param?: IN): OUT;
  annotation(ctx: AnnotationCtx, param?: IN): OUT;
  elementValuePairList(ctx: ElementValuePairListCtx, param?: IN): OUT;
  elementValuePair(ctx: ElementValuePairCtx, param?: IN): OUT;
  elementValue(ctx: ElementValueCtx, param?: IN): OUT;
  elementValueArrayInitializer(
    ctx: ElementValueArrayInitializerCtx,
    param?: IN
  ): OUT;
  elementValueList(ctx: ElementValueListCtx, param?: IN): OUT;
  arrayInitializer(ctx: ArrayInitializerCtx, param?: IN): OUT;
  variableInitializerList(ctx: VariableInitializerListCtx, param?: IN): OUT;
  block(ctx: BlockCtx, param?: IN): OUT;
  blockStatements(ctx: BlockStatementsCtx, param?: IN): OUT;
  blockStatement(ctx: BlockStatementCtx, param?: IN): OUT;
  localVariableDeclarationStatement(
    ctx: LocalVariableDeclarationStatementCtx,
    param?: IN
  ): OUT;
  localVariableDeclaration(ctx: LocalVariableDeclarationCtx, param?: IN): OUT;
  localVariableType(ctx: LocalVariableTypeCtx, param?: IN): OUT;
  statement(ctx: StatementCtx, param?: IN): OUT;
  statementWithoutTrailingSubstatement(
    ctx: StatementWithoutTrailingSubstatementCtx,
    param?: IN
  ): OUT;
  emptyStatement(ctx: EmptyStatementCtx, param?: IN): OUT;
  labeledStatement(ctx: LabeledStatementCtx, param?: IN): OUT;
  expressionStatement(ctx: ExpressionStatementCtx, param?: IN): OUT;
  statementExpression(ctx: StatementExpressionCtx, param?: IN): OUT;
  ifStatement(ctx: IfStatementCtx, param?: IN): OUT;
  assertStatement(ctx: AssertStatementCtx, param?: IN): OUT;
  switchStatement(ctx: SwitchStatementCtx, param?: IN): OUT;
  switchBlock(ctx: SwitchBlockCtx, param?: IN): OUT;
  switchBlockStatementGroup(ctx: SwitchBlockStatementGroupCtx, param?: IN): OUT;
  switchLabel(ctx: SwitchLabelCtx, param?: IN): OUT;
  switchRule(ctx: SwitchRuleCtx, param?: IN): OUT;
  caseConstant(ctx: CaseConstantCtx, param?: IN): OUT;
  whileStatement(ctx: WhileStatementCtx, param?: IN): OUT;
  doStatement(ctx: DoStatementCtx, param?: IN): OUT;
  forStatement(ctx: ForStatementCtx, param?: IN): OUT;
  basicForStatement(ctx: BasicForStatementCtx, param?: IN): OUT;
  forInit(ctx: ForInitCtx, param?: IN): OUT;
  forUpdate(ctx: ForUpdateCtx, param?: IN): OUT;
  statementExpressionList(ctx: StatementExpressionListCtx, param?: IN): OUT;
  enhancedForStatement(ctx: EnhancedForStatementCtx, param?: IN): OUT;
  breakStatement(ctx: BreakStatementCtx, param?: IN): OUT;
  continueStatement(ctx: ContinueStatementCtx, param?: IN): OUT;
  returnStatement(ctx: ReturnStatementCtx, param?: IN): OUT;
  throwStatement(ctx: ThrowStatementCtx, param?: IN): OUT;
  synchronizedStatement(ctx: SynchronizedStatementCtx, param?: IN): OUT;
  tryStatement(ctx: TryStatementCtx, param?: IN): OUT;
  catches(ctx: CatchesCtx, param?: IN): OUT;
  catchClause(ctx: CatchClauseCtx, param?: IN): OUT;
  catchFormalParameter(ctx: CatchFormalParameterCtx, param?: IN): OUT;
  catchType(ctx: CatchTypeCtx, param?: IN): OUT;
  finally(ctx: FinallyCtx, param?: IN): OUT;
  tryWithResourcesStatement(ctx: TryWithResourcesStatementCtx, param?: IN): OUT;
  resourceSpecification(ctx: ResourceSpecificationCtx, param?: IN): OUT;
  resourceList(ctx: ResourceListCtx, param?: IN): OUT;
  resource(ctx: ResourceCtx, param?: IN): OUT;
  yieldStatement(ctx: YieldStatementCtx, param?: IN): OUT;
  variableAccess(ctx: VariableAccessCtx, param?: IN): OUT;
  expression(ctx: ExpressionCtx, param?: IN): OUT;
  lambdaExpression(ctx: LambdaExpressionCtx, param?: IN): OUT;
  lambdaParameters(ctx: LambdaParametersCtx, param?: IN): OUT;
  lambdaParametersWithBraces(
    ctx: LambdaParametersWithBracesCtx,
    param?: IN
  ): OUT;
  lambdaParameterList(ctx: LambdaParameterListCtx, param?: IN): OUT;
  inferredLambdaParameterList(
    ctx: InferredLambdaParameterListCtx,
    param?: IN
  ): OUT;
  explicitLambdaParameterList(
    ctx: ExplicitLambdaParameterListCtx,
    param?: IN
  ): OUT;
  lambdaParameter(ctx: LambdaParameterCtx, param?: IN): OUT;
  regularLambdaParameter(ctx: RegularLambdaParameterCtx, param?: IN): OUT;
  lambdaParameterType(ctx: LambdaParameterTypeCtx, param?: IN): OUT;
  lambdaBody(ctx: LambdaBodyCtx, param?: IN): OUT;
  ternaryExpression(ctx: TernaryExpressionCtx, param?: IN): OUT;
  binaryExpression(ctx: BinaryExpressionCtx, param?: IN): OUT;
  unaryExpression(ctx: UnaryExpressionCtx, param?: IN): OUT;
  unaryExpressionNotPlusMinus(
    ctx: UnaryExpressionNotPlusMinusCtx,
    param?: IN
  ): OUT;
  primary(ctx: PrimaryCtx, param?: IN): OUT;
  primaryPrefix(ctx: PrimaryPrefixCtx, param?: IN): OUT;
  primarySuffix(ctx: PrimarySuffixCtx, param?: IN): OUT;
  fqnOrRefType(ctx: FqnOrRefTypeCtx, param?: IN): OUT;
  fqnOrRefTypePartRest(ctx: FqnOrRefTypePartRestCtx, param?: IN): OUT;
  fqnOrRefTypePartCommon(ctx: FqnOrRefTypePartCommonCtx, param?: IN): OUT;
  fqnOrRefTypePartFirst(ctx: FqnOrRefTypePartFirstCtx, param?: IN): OUT;
  parenthesisExpression(ctx: ParenthesisExpressionCtx, param?: IN): OUT;
  castExpression(ctx: CastExpressionCtx, param?: IN): OUT;
  primitiveCastExpression(ctx: PrimitiveCastExpressionCtx, param?: IN): OUT;
  referenceTypeCastExpression(
    ctx: ReferenceTypeCastExpressionCtx,
    param?: IN
  ): OUT;
  newExpression(ctx: NewExpressionCtx, param?: IN): OUT;
  unqualifiedClassInstanceCreationExpression(
    ctx: UnqualifiedClassInstanceCreationExpressionCtx,
    param?: IN
  ): OUT;
  classOrInterfaceTypeToInstantiate(
    ctx: ClassOrInterfaceTypeToInstantiateCtx,
    param?: IN
  ): OUT;
  typeArgumentsOrDiamond(ctx: TypeArgumentsOrDiamondCtx, param?: IN): OUT;
  diamond(ctx: DiamondCtx, param?: IN): OUT;
  methodInvocationSuffix(ctx: MethodInvocationSuffixCtx, param?: IN): OUT;
  argumentList(ctx: ArgumentListCtx, param?: IN): OUT;
  arrayCreationExpression(ctx: ArrayCreationExpressionCtx, param?: IN): OUT;
  arrayCreationDefaultInitSuffix(
    ctx: ArrayCreationDefaultInitSuffixCtx,
    param?: IN
  ): OUT;
  arrayCreationExplicitInitSuffix(
    ctx: ArrayCreationExplicitInitSuffixCtx,
    param?: IN
  ): OUT;
  dimExprs(ctx: DimExprsCtx, param?: IN): OUT;
  dimExpr(ctx: DimExprCtx, param?: IN): OUT;
  classLiteralSuffix(ctx: ClassLiteralSuffixCtx, param?: IN): OUT;
  arrayAccessSuffix(ctx: ArrayAccessSuffixCtx, param?: IN): OUT;
  methodReferenceSuffix(ctx: MethodReferenceSuffixCtx, param?: IN): OUT;
  templateArgument(ctx: TemplateArgumentCtx, param?: IN): OUT;
  template(ctx: TemplateCtx, param?: IN): OUT;
  stringTemplate(ctx: StringTemplateCtx, param?: IN): OUT;
  textBlockTemplate(ctx: TextBlockTemplateCtx, param?: IN): OUT;
  embeddedExpression(ctx: EmbeddedExpressionCtx, param?: IN): OUT;
  pattern(ctx: PatternCtx, param?: IN): OUT;
  typePattern(ctx: TypePatternCtx, param?: IN): OUT;
  recordPattern(ctx: RecordPatternCtx, param?: IN): OUT;
  componentPatternList(ctx: ComponentPatternListCtx, param?: IN): OUT;
  componentPattern(ctx: ComponentPatternCtx, param?: IN): OUT;
  unnamedPattern(ctx: UnnamedPatternCtx, param?: IN): OUT;
  guard(ctx: GuardCtx, param?: IN): OUT;
  isRefTypeInMethodRef(ctx: IsRefTypeInMethodRefCtx, param?: IN): OUT;
}

interface JavaCstVisitorConstructor<IN, OUT> {
  new (): JavaCstVisitor<IN, OUT>;
}

export abstract class JavaCstVisitorWithDefaults<IN, OUT>
  implements ICstVisitor<IN, OUT>
{
  // No need to implement these two methods
  // Generic Visit method implemented by the Chevrotain Library
  visit(cstNode: CstNode | CstNode[], param?: IN): OUT;
  validateVisitor(): void;

  typeIdentifier(ctx: TypeIdentifierCtx, param?: IN): OUT;
  literal(ctx: LiteralCtx, param?: IN): OUT;
  integerLiteral(ctx: IntegerLiteralCtx, param?: IN): OUT;
  floatingPointLiteral(ctx: FloatingPointLiteralCtx, param?: IN): OUT;
  booleanLiteral(ctx: BooleanLiteralCtx, param?: IN): OUT;
  primitiveType(ctx: PrimitiveTypeCtx, param?: IN): OUT;
  numericType(ctx: NumericTypeCtx, param?: IN): OUT;
  integralType(ctx: IntegralTypeCtx, param?: IN): OUT;
  floatingPointType(ctx: FloatingPointTypeCtx, param?: IN): OUT;
  referenceType(ctx: ReferenceTypeCtx, param?: IN): OUT;
  classOrInterfaceType(ctx: ClassOrInterfaceTypeCtx, param?: IN): OUT;
  classType(ctx: ClassTypeCtx, param?: IN): OUT;
  interfaceType(ctx: InterfaceTypeCtx, param?: IN): OUT;
  typeVariable(ctx: TypeVariableCtx, param?: IN): OUT;
  dims(ctx: DimsCtx, param?: IN): OUT;
  typeParameter(ctx: TypeParameterCtx, param?: IN): OUT;
  typeParameterModifier(ctx: TypeParameterModifierCtx, param?: IN): OUT;
  typeBound(ctx: TypeBoundCtx, param?: IN): OUT;
  additionalBound(ctx: AdditionalBoundCtx, param?: IN): OUT;
  typeArguments(ctx: TypeArgumentsCtx, param?: IN): OUT;
  typeArgumentList(ctx: TypeArgumentListCtx, param?: IN): OUT;
  typeArgument(ctx: TypeArgumentCtx, param?: IN): OUT;
  wildcard(ctx: WildcardCtx, param?: IN): OUT;
  wildcardBounds(ctx: WildcardBoundsCtx, param?: IN): OUT;
  moduleName(ctx: ModuleNameCtx, param?: IN): OUT;
  packageName(ctx: PackageNameCtx, param?: IN): OUT;
  typeName(ctx: TypeNameCtx, param?: IN): OUT;
  expressionName(ctx: ExpressionNameCtx, param?: IN): OUT;
  methodName(ctx: MethodNameCtx, param?: IN): OUT;
  packageOrTypeName(ctx: PackageOrTypeNameCtx, param?: IN): OUT;
  ambiguousName(ctx: AmbiguousNameCtx, param?: IN): OUT;
  classDeclaration(ctx: ClassDeclarationCtx, param?: IN): OUT;
  normalClassDeclaration(ctx: NormalClassDeclarationCtx, param?: IN): OUT;
  classModifier(ctx: ClassModifierCtx, param?: IN): OUT;
  typeParameters(ctx: TypeParametersCtx, param?: IN): OUT;
  typeParameterList(ctx: TypeParameterListCtx, param?: IN): OUT;
  superclass(ctx: SuperclassCtx, param?: IN): OUT;
  superinterfaces(ctx: SuperinterfacesCtx, param?: IN): OUT;
  interfaceTypeList(ctx: InterfaceTypeListCtx, param?: IN): OUT;
  classPermits(ctx: ClassPermitsCtx, param?: IN): OUT;
  classBody(ctx: ClassBodyCtx, param?: IN): OUT;
  classBodyDeclaration(ctx: ClassBodyDeclarationCtx, param?: IN): OUT;
  classMemberDeclaration(ctx: ClassMemberDeclarationCtx, param?: IN): OUT;
  fieldDeclaration(ctx: FieldDeclarationCtx, param?: IN): OUT;
  fieldModifier(ctx: FieldModifierCtx, param?: IN): OUT;
  variableDeclaratorList(ctx: VariableDeclaratorListCtx, param?: IN): OUT;
  variableDeclarator(ctx: VariableDeclaratorCtx, param?: IN): OUT;
  variableDeclaratorId(ctx: VariableDeclaratorIdCtx, param?: IN): OUT;
  variableInitializer(ctx: VariableInitializerCtx, param?: IN): OUT;
  unannType(ctx: UnannTypeCtx, param?: IN): OUT;
  unannPrimitiveTypeWithOptionalDimsSuffix(
    ctx: UnannPrimitiveTypeWithOptionalDimsSuffixCtx,
    param?: IN
  ): OUT;
  unannPrimitiveType(ctx: UnannPrimitiveTypeCtx, param?: IN): OUT;
  unannReferenceType(ctx: UnannReferenceTypeCtx, param?: IN): OUT;
  unannClassOrInterfaceType(ctx: UnannClassOrInterfaceTypeCtx, param?: IN): OUT;
  unannClassType(ctx: UnannClassTypeCtx, param?: IN): OUT;
  unannInterfaceType(ctx: UnannInterfaceTypeCtx, param?: IN): OUT;
  unannTypeVariable(ctx: UnannTypeVariableCtx, param?: IN): OUT;
  methodDeclaration(ctx: MethodDeclarationCtx, param?: IN): OUT;
  methodModifier(ctx: MethodModifierCtx, param?: IN): OUT;
  methodHeader(ctx: MethodHeaderCtx, param?: IN): OUT;
  result(ctx: ResultCtx, param?: IN): OUT;
  methodDeclarator(ctx: MethodDeclaratorCtx, param?: IN): OUT;
  receiverParameter(ctx: ReceiverParameterCtx, param?: IN): OUT;
  formalParameterList(ctx: FormalParameterListCtx, param?: IN): OUT;
  formalParameter(ctx: FormalParameterCtx, param?: IN): OUT;
  variableParaRegularParameter(
    ctx: VariableParaRegularParameterCtx,
    param?: IN
  ): OUT;
  variableArityParameter(ctx: VariableArityParameterCtx, param?: IN): OUT;
  variableModifier(ctx: VariableModifierCtx, param?: IN): OUT;
  throws(ctx: ThrowsCtx, param?: IN): OUT;
  exceptionTypeList(ctx: ExceptionTypeListCtx, param?: IN): OUT;
  exceptionType(ctx: ExceptionTypeCtx, param?: IN): OUT;
  methodBody(ctx: MethodBodyCtx, param?: IN): OUT;
  instanceInitializer(ctx: InstanceInitializerCtx, param?: IN): OUT;
  staticInitializer(ctx: StaticInitializerCtx, param?: IN): OUT;
  constructorDeclaration(ctx: ConstructorDeclarationCtx, param?: IN): OUT;
  constructorModifier(ctx: ConstructorModifierCtx, param?: IN): OUT;
  constructorDeclarator(ctx: ConstructorDeclaratorCtx, param?: IN): OUT;
  simpleTypeName(ctx: SimpleTypeNameCtx, param?: IN): OUT;
  constructorBody(ctx: ConstructorBodyCtx, param?: IN): OUT;
  explicitConstructorInvocation(
    ctx: ExplicitConstructorInvocationCtx,
    param?: IN
  ): OUT;
  unqualifiedExplicitConstructorInvocation(
    ctx: UnqualifiedExplicitConstructorInvocationCtx,
    param?: IN
  ): OUT;
  qualifiedExplicitConstructorInvocation(
    ctx: QualifiedExplicitConstructorInvocationCtx,
    param?: IN
  ): OUT;
  enumDeclaration(ctx: EnumDeclarationCtx, param?: IN): OUT;
  enumBody(ctx: EnumBodyCtx, param?: IN): OUT;
  enumConstantList(ctx: EnumConstantListCtx, param?: IN): OUT;
  enumConstant(ctx: EnumConstantCtx, param?: IN): OUT;
  enumConstantModifier(ctx: EnumConstantModifierCtx, param?: IN): OUT;
  enumBodyDeclarations(ctx: EnumBodyDeclarationsCtx, param?: IN): OUT;
  recordDeclaration(ctx: RecordDeclarationCtx, param?: IN): OUT;
  recordHeader(ctx: RecordHeaderCtx, param?: IN): OUT;
  recordComponentList(ctx: RecordComponentListCtx, param?: IN): OUT;
  recordComponent(ctx: RecordComponentCtx, param?: IN): OUT;
  variableArityRecordComponent(
    ctx: VariableArityRecordComponentCtx,
    param?: IN
  ): OUT;
  recordComponentModifier(ctx: RecordComponentModifierCtx, param?: IN): OUT;
  recordBody(ctx: RecordBodyCtx, param?: IN): OUT;
  recordBodyDeclaration(ctx: RecordBodyDeclarationCtx, param?: IN): OUT;
  compactConstructorDeclaration(
    ctx: CompactConstructorDeclarationCtx,
    param?: IN
  ): OUT;
  isDims(ctx: IsDimsCtx, param?: IN): OUT;
  compilationUnit(ctx: CompilationUnitCtx, param?: IN): OUT;
  ordinaryCompilationUnit(ctx: OrdinaryCompilationUnitCtx, param?: IN): OUT;
  modularCompilationUnit(ctx: ModularCompilationUnitCtx, param?: IN): OUT;
  packageDeclaration(ctx: PackageDeclarationCtx, param?: IN): OUT;
  packageModifier(ctx: PackageModifierCtx, param?: IN): OUT;
  importDeclaration(ctx: ImportDeclarationCtx, param?: IN): OUT;
  typeDeclaration(ctx: TypeDeclarationCtx, param?: IN): OUT;
  moduleDeclaration(ctx: ModuleDeclarationCtx, param?: IN): OUT;
  moduleDirective(ctx: ModuleDirectiveCtx, param?: IN): OUT;
  requiresModuleDirective(ctx: RequiresModuleDirectiveCtx, param?: IN): OUT;
  exportsModuleDirective(ctx: ExportsModuleDirectiveCtx, param?: IN): OUT;
  opensModuleDirective(ctx: OpensModuleDirectiveCtx, param?: IN): OUT;
  usesModuleDirective(ctx: UsesModuleDirectiveCtx, param?: IN): OUT;
  providesModuleDirective(ctx: ProvidesModuleDirectiveCtx, param?: IN): OUT;
  requiresModifier(ctx: RequiresModifierCtx, param?: IN): OUT;
  interfaceDeclaration(ctx: InterfaceDeclarationCtx, param?: IN): OUT;
  normalInterfaceDeclaration(
    ctx: NormalInterfaceDeclarationCtx,
    param?: IN
  ): OUT;
  interfaceModifier(ctx: InterfaceModifierCtx, param?: IN): OUT;
  extendsInterfaces(ctx: ExtendsInterfacesCtx, param?: IN): OUT;
  interfacePermits(ctx: InterfacePermitsCtx, param?: IN): OUT;
  interfaceBody(ctx: InterfaceBodyCtx, param?: IN): OUT;
  interfaceMemberDeclaration(
    ctx: InterfaceMemberDeclarationCtx,
    param?: IN
  ): OUT;
  constantDeclaration(ctx: ConstantDeclarationCtx, param?: IN): OUT;
  constantModifier(ctx: ConstantModifierCtx, param?: IN): OUT;
  interfaceMethodDeclaration(
    ctx: InterfaceMethodDeclarationCtx,
    param?: IN
  ): OUT;
  interfaceMethodModifier(ctx: InterfaceMethodModifierCtx, param?: IN): OUT;
  annotationTypeDeclaration(ctx: AnnotationTypeDeclarationCtx, param?: IN): OUT;
  annotationTypeBody(ctx: AnnotationTypeBodyCtx, param?: IN): OUT;
  annotationTypeMemberDeclaration(
    ctx: AnnotationTypeMemberDeclarationCtx,
    param?: IN
  ): OUT;
  annotationTypeElementDeclaration(
    ctx: AnnotationTypeElementDeclarationCtx,
    param?: IN
  ): OUT;
  annotationTypeElementModifier(
    ctx: AnnotationTypeElementModifierCtx,
    param?: IN
  ): OUT;
  defaultValue(ctx: DefaultValueCtx, param?: IN): OUT;
  annotation(ctx: AnnotationCtx, param?: IN): OUT;
  elementValuePairList(ctx: ElementValuePairListCtx, param?: IN): OUT;
  elementValuePair(ctx: ElementValuePairCtx, param?: IN): OUT;
  elementValue(ctx: ElementValueCtx, param?: IN): OUT;
  elementValueArrayInitializer(
    ctx: ElementValueArrayInitializerCtx,
    param?: IN
  ): OUT;
  elementValueList(ctx: ElementValueListCtx, param?: IN): OUT;
  arrayInitializer(ctx: ArrayInitializerCtx, param?: IN): OUT;
  variableInitializerList(ctx: VariableInitializerListCtx, param?: IN): OUT;
  block(ctx: BlockCtx, param?: IN): OUT;
  blockStatements(ctx: BlockStatementsCtx, param?: IN): OUT;
  blockStatement(ctx: BlockStatementCtx, param?: IN): OUT;
  localVariableDeclarationStatement(
    ctx: LocalVariableDeclarationStatementCtx,
    param?: IN
  ): OUT;
  localVariableDeclaration(ctx: LocalVariableDeclarationCtx, param?: IN): OUT;
  localVariableType(ctx: LocalVariableTypeCtx, param?: IN): OUT;
  statement(ctx: StatementCtx, param?: IN): OUT;
  statementWithoutTrailingSubstatement(
    ctx: StatementWithoutTrailingSubstatementCtx,
    param?: IN
  ): OUT;
  emptyStatement(ctx: EmptyStatementCtx, param?: IN): OUT;
  labeledStatement(ctx: LabeledStatementCtx, param?: IN): OUT;
  expressionStatement(ctx: ExpressionStatementCtx, param?: IN): OUT;
  statementExpression(ctx: StatementExpressionCtx, param?: IN): OUT;
  ifStatement(ctx: IfStatementCtx, param?: IN): OUT;
  assertStatement(ctx: AssertStatementCtx, param?: IN): OUT;
  switchStatement(ctx: SwitchStatementCtx, param?: IN): OUT;
  switchBlock(ctx: SwitchBlockCtx, param?: IN): OUT;
  switchBlockStatementGroup(ctx: SwitchBlockStatementGroupCtx, param?: IN): OUT;
  switchLabel(ctx: SwitchLabelCtx, param?: IN): OUT;
  switchRule(ctx: SwitchRuleCtx, param?: IN): OUT;
  caseConstant(ctx: CaseConstantCtx, param?: IN): OUT;
  whileStatement(ctx: WhileStatementCtx, param?: IN): OUT;
  doStatement(ctx: DoStatementCtx, param?: IN): OUT;
  forStatement(ctx: ForStatementCtx, param?: IN): OUT;
  basicForStatement(ctx: BasicForStatementCtx, param?: IN): OUT;
  forInit(ctx: ForInitCtx, param?: IN): OUT;
  forUpdate(ctx: ForUpdateCtx, param?: IN): OUT;
  statementExpressionList(ctx: StatementExpressionListCtx, param?: IN): OUT;
  enhancedForStatement(ctx: EnhancedForStatementCtx, param?: IN): OUT;
  breakStatement(ctx: BreakStatementCtx, param?: IN): OUT;
  continueStatement(ctx: ContinueStatementCtx, param?: IN): OUT;
  returnStatement(ctx: ReturnStatementCtx, param?: IN): OUT;
  throwStatement(ctx: ThrowStatementCtx, param?: IN): OUT;
  synchronizedStatement(ctx: SynchronizedStatementCtx, param?: IN): OUT;
  tryStatement(ctx: TryStatementCtx, param?: IN): OUT;
  catches(ctx: CatchesCtx, param?: IN): OUT;
  catchClause(ctx: CatchClauseCtx, param?: IN): OUT;
  catchFormalParameter(ctx: CatchFormalParameterCtx, param?: IN): OUT;
  catchType(ctx: CatchTypeCtx, param?: IN): OUT;
  finally(ctx: FinallyCtx, param?: IN): OUT;
  tryWithResourcesStatement(ctx: TryWithResourcesStatementCtx, param?: IN): OUT;
  resourceSpecification(ctx: ResourceSpecificationCtx, param?: IN): OUT;
  resourceList(ctx: ResourceListCtx, param?: IN): OUT;
  resource(ctx: ResourceCtx, param?: IN): OUT;
  yieldStatement(ctx: YieldStatementCtx, param?: IN): OUT;
  variableAccess(ctx: VariableAccessCtx, param?: IN): OUT;
  expression(ctx: ExpressionCtx, param?: IN): OUT;
  lambdaExpression(ctx: LambdaExpressionCtx, param?: IN): OUT;
  lambdaParameters(ctx: LambdaParametersCtx, param?: IN): OUT;
  lambdaParametersWithBraces(
    ctx: LambdaParametersWithBracesCtx,
    param?: IN
  ): OUT;
  lambdaParameterList(ctx: LambdaParameterListCtx, param?: IN): OUT;
  inferredLambdaParameterList(
    ctx: InferredLambdaParameterListCtx,
    param?: IN
  ): OUT;
  explicitLambdaParameterList(
    ctx: ExplicitLambdaParameterListCtx,
    param?: IN
  ): OUT;
  lambdaParameter(ctx: LambdaParameterCtx, param?: IN): OUT;
  regularLambdaParameter(ctx: RegularLambdaParameterCtx, param?: IN): OUT;
  lambdaParameterType(ctx: LambdaParameterTypeCtx, param?: IN): OUT;
  lambdaBody(ctx: LambdaBodyCtx, param?: IN): OUT;
  ternaryExpression(ctx: TernaryExpressionCtx, param?: IN): OUT;
  binaryExpression(ctx: BinaryExpressionCtx, param?: IN): OUT;
  unaryExpression(ctx: UnaryExpressionCtx, param?: IN): OUT;
  unaryExpressionNotPlusMinus(
    ctx: UnaryExpressionNotPlusMinusCtx,
    param?: IN
  ): OUT;
  primary(ctx: PrimaryCtx, param?: IN): OUT;
  primaryPrefix(ctx: PrimaryPrefixCtx, param?: IN): OUT;
  primarySuffix(ctx: PrimarySuffixCtx, param?: IN): OUT;
  fqnOrRefType(ctx: FqnOrRefTypeCtx, param?: IN): OUT;
  fqnOrRefTypePartRest(ctx: FqnOrRefTypePartRestCtx, param?: IN): OUT;
  fqnOrRefTypePartCommon(ctx: FqnOrRefTypePartCommonCtx, param?: IN): OUT;
  fqnOrRefTypePartFirst(ctx: FqnOrRefTypePartFirstCtx, param?: IN): OUT;
  parenthesisExpression(ctx: ParenthesisExpressionCtx, param?: IN): OUT;
  castExpression(ctx: CastExpressionCtx, param?: IN): OUT;
  primitiveCastExpression(ctx: PrimitiveCastExpressionCtx, param?: IN): OUT;
  referenceTypeCastExpression(
    ctx: ReferenceTypeCastExpressionCtx,
    param?: IN
  ): OUT;
  newExpression(ctx: NewExpressionCtx, param?: IN): OUT;
  unqualifiedClassInstanceCreationExpression(
    ctx: UnqualifiedClassInstanceCreationExpressionCtx,
    param?: IN
  ): OUT;
  classOrInterfaceTypeToInstantiate(
    ctx: ClassOrInterfaceTypeToInstantiateCtx,
    param?: IN
  ): OUT;
  typeArgumentsOrDiamond(ctx: TypeArgumentsOrDiamondCtx, param?: IN): OUT;
  diamond(ctx: DiamondCtx, param?: IN): OUT;
  methodInvocationSuffix(ctx: MethodInvocationSuffixCtx, param?: IN): OUT;
  argumentList(ctx: ArgumentListCtx, param?: IN): OUT;
  arrayCreationExpression(ctx: ArrayCreationExpressionCtx, param?: IN): OUT;
  arrayCreationDefaultInitSuffix(
    ctx: ArrayCreationDefaultInitSuffixCtx,
    param?: IN
  ): OUT;
  arrayCreationExplicitInitSuffix(
    ctx: ArrayCreationExplicitInitSuffixCtx,
    param?: IN
  ): OUT;
  dimExprs(ctx: DimExprsCtx, param?: IN): OUT;
  dimExpr(ctx: DimExprCtx, param?: IN): OUT;
  classLiteralSuffix(ctx: ClassLiteralSuffixCtx, param?: IN): OUT;
  arrayAccessSuffix(ctx: ArrayAccessSuffixCtx, param?: IN): OUT;
  methodReferenceSuffix(ctx: MethodReferenceSuffixCtx, param?: IN): OUT;
  templateArgument(ctx: TemplateArgumentCtx, param?: IN): OUT;
  template(ctx: TemplateCtx, param?: IN): OUT;
  stringTemplate(ctx: StringTemplateCtx, param?: IN): OUT;
  textBlockTemplate(ctx: TextBlockTemplateCtx, param?: IN): OUT;
  embeddedExpression(ctx: EmbeddedExpressionCtx, param?: IN): OUT;
  pattern(ctx: PatternCtx, param?: IN): OUT;
  typePattern(ctx: TypePatternCtx, param?: IN): OUT;
  recordPattern(ctx: RecordPatternCtx, param?: IN): OUT;
  componentPatternList(ctx: ComponentPatternListCtx, param?: IN): OUT;
  componentPattern(ctx: ComponentPatternCtx, param?: IN): OUT;
  unnamedPattern(ctx: UnnamedPatternCtx, param?: IN): OUT;
  guard(ctx: GuardCtx, param?: IN): OUT;
  isRefTypeInMethodRef(ctx: IsRefTypeInMethodRefCtx, param?: IN): OUT;
}

interface JavaCstVisitorWithDefaultsConstructor<IN, OUT> {
  new (): JavaCstVisitorWithDefaults<IN, OUT>;
}
export interface TypeIdentifierCstNode extends CstNode {
  name: "typeIdentifier";
  children: TypeIdentifierCtx;
}

export type TypeIdentifierCtx = {
  Identifier: IToken[];
};

export interface LiteralCstNode extends CstNode {
  name: "literal";
  children: LiteralCtx;
}

export type LiteralCtx = {
  integerLiteral?: IntegerLiteralCstNode[];
  floatingPointLiteral?: FloatingPointLiteralCstNode[];
  booleanLiteral?: BooleanLiteralCstNode[];
  CharLiteral?: IToken[];
  TextBlock?: IToken[];
  StringLiteral?: IToken[];
  Null?: IToken[];
};

export interface IntegerLiteralCstNode extends CstNode {
  name: "integerLiteral";
  children: IntegerLiteralCtx;
}

export type IntegerLiteralCtx = {
  DecimalLiteral?: IToken[];
  HexLiteral?: IToken[];
  OctalLiteral?: IToken[];
  BinaryLiteral?: IToken[];
};

export interface FloatingPointLiteralCstNode extends CstNode {
  name: "floatingPointLiteral";
  children: FloatingPointLiteralCtx;
}

export type FloatingPointLiteralCtx = {
  FloatLiteral?: IToken[];
  HexFloatLiteral?: IToken[];
};

export interface BooleanLiteralCstNode extends CstNode {
  name: "booleanLiteral";
  children: BooleanLiteralCtx;
}

export type BooleanLiteralCtx = {
  True?: IToken[];
  False?: IToken[];
};

export interface PrimitiveTypeCstNode extends CstNode {
  name: "primitiveType";
  children: PrimitiveTypeCtx;
}

export type PrimitiveTypeCtx = {
  annotation?: AnnotationCstNode[];
  numericType?: NumericTypeCstNode[];
  Boolean?: IToken[];
};

export interface NumericTypeCstNode extends CstNode {
  name: "numericType";
  children: NumericTypeCtx;
}

export type NumericTypeCtx = {
  integralType?: IntegralTypeCstNode[];
  floatingPointType?: FloatingPointTypeCstNode[];
};

export interface IntegralTypeCstNode extends CstNode {
  name: "integralType";
  children: IntegralTypeCtx;
}

export type IntegralTypeCtx = {
  Byte?: IToken[];
  Short?: IToken[];
  Int?: IToken[];
  Long?: IToken[];
  Char?: IToken[];
};

export interface FloatingPointTypeCstNode extends CstNode {
  name: "floatingPointType";
  children: FloatingPointTypeCtx;
}

export type FloatingPointTypeCtx = {
  Float?: IToken[];
  Double?: IToken[];
};

export interface ReferenceTypeCstNode extends CstNode {
  name: "referenceType";
  children: ReferenceTypeCtx;
}

export type ReferenceTypeCtx = {
  annotation?: AnnotationCstNode[];
  primitiveType?: PrimitiveTypeCstNode[];
  dims?: DimsCstNode[];
  classOrInterfaceType?: ClassOrInterfaceTypeCstNode[];
};

export interface ClassOrInterfaceTypeCstNode extends CstNode {
  name: "classOrInterfaceType";
  children: ClassOrInterfaceTypeCtx;
}

export type ClassOrInterfaceTypeCtx = {
  classType: ClassTypeCstNode[];
};

export interface ClassTypeCstNode extends CstNode {
  name: "classType";
  children: ClassTypeCtx;
}

export type ClassTypeCtx = {
  annotation?: AnnotationCstNode[];
  Identifier: IToken[];
  typeArguments?: TypeArgumentsCstNode[];
  Dot?: IToken[];
};

export interface InterfaceTypeCstNode extends CstNode {
  name: "interfaceType";
  children: InterfaceTypeCtx;
}

export type InterfaceTypeCtx = {
  classType: ClassTypeCstNode[];
};

export interface TypeVariableCstNode extends CstNode {
  name: "typeVariable";
  children: TypeVariableCtx;
}

export type TypeVariableCtx = {
  annotation?: AnnotationCstNode[];
  Identifier: IToken[];
};

export interface DimsCstNode extends CstNode {
  name: "dims";
  children: DimsCtx;
}

export type DimsCtx = {
  annotation?: AnnotationCstNode[];
  LSquare: IToken[];
  RSquare: IToken[];
};

export interface TypeParameterCstNode extends CstNode {
  name: "typeParameter";
  children: TypeParameterCtx;
}

export type TypeParameterCtx = {
  typeParameterModifier?: TypeParameterModifierCstNode[];
  typeIdentifier: TypeIdentifierCstNode[];
  typeBound?: TypeBoundCstNode[];
};

export interface TypeParameterModifierCstNode extends CstNode {
  name: "typeParameterModifier";
  children: TypeParameterModifierCtx;
}

export type TypeParameterModifierCtx = {
  annotation: AnnotationCstNode[];
};

export interface TypeBoundCstNode extends CstNode {
  name: "typeBound";
  children: TypeBoundCtx;
}

export type TypeBoundCtx = {
  Extends: IToken[];
  classOrInterfaceType: ClassOrInterfaceTypeCstNode[];
  additionalBound?: AdditionalBoundCstNode[];
};

export interface AdditionalBoundCstNode extends CstNode {
  name: "additionalBound";
  children: AdditionalBoundCtx;
}

export type AdditionalBoundCtx = {
  And: IToken[];
  interfaceType: InterfaceTypeCstNode[];
};

export interface TypeArgumentsCstNode extends CstNode {
  name: "typeArguments";
  children: TypeArgumentsCtx;
}

export type TypeArgumentsCtx = {
  Less: IToken[];
  typeArgumentList: TypeArgumentListCstNode[];
  Greater: IToken[];
};

export interface TypeArgumentListCstNode extends CstNode {
  name: "typeArgumentList";
  children: TypeArgumentListCtx;
}

export type TypeArgumentListCtx = {
  typeArgument: TypeArgumentCstNode[];
  Comma?: IToken[];
};

export interface TypeArgumentCstNode extends CstNode {
  name: "typeArgument";
  children: TypeArgumentCtx;
}

export type TypeArgumentCtx = {
  referenceType?: ReferenceTypeCstNode[];
  wildcard?: WildcardCstNode[];
};

export interface WildcardCstNode extends CstNode {
  name: "wildcard";
  children: WildcardCtx;
}

export type WildcardCtx = {
  annotation?: AnnotationCstNode[];
  QuestionMark: IToken[];
  wildcardBounds?: WildcardBoundsCstNode[];
};

export interface WildcardBoundsCstNode extends CstNode {
  name: "wildcardBounds";
  children: WildcardBoundsCtx;
}

export type WildcardBoundsCtx = {
  Extends?: IToken[];
  Super?: IToken[];
  referenceType: ReferenceTypeCstNode[];
};

export interface ModuleNameCstNode extends CstNode {
  name: "moduleName";
  children: ModuleNameCtx;
}

export type ModuleNameCtx = {
  Identifier: IToken[];
  Dot?: IToken[];
};

export interface PackageNameCstNode extends CstNode {
  name: "packageName";
  children: PackageNameCtx;
}

export type PackageNameCtx = {
  Identifier: IToken[];
  Dot?: IToken[];
};

export interface TypeNameCstNode extends CstNode {
  name: "typeName";
  children: TypeNameCtx;
}

export type TypeNameCtx = {
  Identifier: IToken[];
  Dot?: IToken[];
};

export interface ExpressionNameCstNode extends CstNode {
  name: "expressionName";
  children: ExpressionNameCtx;
}

export type ExpressionNameCtx = {
  Identifier: IToken[];
  Dot?: IToken[];
};

export interface MethodNameCstNode extends CstNode {
  name: "methodName";
  children: MethodNameCtx;
}

export type MethodNameCtx = {
  Identifier: IToken[];
};

export interface PackageOrTypeNameCstNode extends CstNode {
  name: "packageOrTypeName";
  children: PackageOrTypeNameCtx;
}

export type PackageOrTypeNameCtx = {
  Identifier: IToken[];
  Dot?: IToken[];
};

export interface AmbiguousNameCstNode extends CstNode {
  name: "ambiguousName";
  children: AmbiguousNameCtx;
}

export type AmbiguousNameCtx = {
  Identifier: IToken[];
  Dot?: IToken[];
};

export interface ClassDeclarationCstNode extends CstNode {
  name: "classDeclaration";
  children: ClassDeclarationCtx;
}

export type ClassDeclarationCtx = {
  classModifier?: ClassModifierCstNode[];
  normalClassDeclaration?: NormalClassDeclarationCstNode[];
  enumDeclaration?: EnumDeclarationCstNode[];
  recordDeclaration?: RecordDeclarationCstNode[];
};

export interface NormalClassDeclarationCstNode extends CstNode {
  name: "normalClassDeclaration";
  children: NormalClassDeclarationCtx;
}

export type NormalClassDeclarationCtx = {
  Class: IToken[];
  typeIdentifier: TypeIdentifierCstNode[];
  typeParameters?: TypeParametersCstNode[];
  superclass?: SuperclassCstNode[];
  superinterfaces?: SuperinterfacesCstNode[];
  classPermits?: ClassPermitsCstNode[];
  classBody: ClassBodyCstNode[];
};

export interface ClassModifierCstNode extends CstNode {
  name: "classModifier";
  children: ClassModifierCtx;
}

export type ClassModifierCtx = {
  annotation?: AnnotationCstNode[];
  Public?: IToken[];
  Protected?: IToken[];
  Private?: IToken[];
  Abstract?: IToken[];
  Static?: IToken[];
  Final?: IToken[];
  Sealed?: IToken[];
  NonSealed?: IToken[];
  Strictfp?: IToken[];
};

export interface TypeParametersCstNode extends CstNode {
  name: "typeParameters";
  children: TypeParametersCtx;
}

export type TypeParametersCtx = {
  Less: IToken[];
  typeParameterList: TypeParameterListCstNode[];
  Greater: IToken[];
};

export interface TypeParameterListCstNode extends CstNode {
  name: "typeParameterList";
  children: TypeParameterListCtx;
}

export type TypeParameterListCtx = {
  typeParameter: TypeParameterCstNode[];
  Comma?: IToken[];
};

export interface SuperclassCstNode extends CstNode {
  name: "superclass";
  children: SuperclassCtx;
}

export type SuperclassCtx = {
  Extends: IToken[];
  classType: ClassTypeCstNode[];
};

export interface SuperinterfacesCstNode extends CstNode {
  name: "superinterfaces";
  children: SuperinterfacesCtx;
}

export type SuperinterfacesCtx = {
  Implements: IToken[];
  interfaceTypeList: InterfaceTypeListCstNode[];
};

export interface InterfaceTypeListCstNode extends CstNode {
  name: "interfaceTypeList";
  children: InterfaceTypeListCtx;
}

export type InterfaceTypeListCtx = {
  interfaceType: InterfaceTypeCstNode[];
  Comma?: IToken[];
};

export interface ClassPermitsCstNode extends CstNode {
  name: "classPermits";
  children: ClassPermitsCtx;
}

export type ClassPermitsCtx = {
  Permits: IToken[];
  typeName: TypeNameCstNode[];
  Comma?: IToken[];
};

export interface ClassBodyCstNode extends CstNode {
  name: "classBody";
  children: ClassBodyCtx;
}

export type ClassBodyCtx = {
  LCurly: IToken[];
  classBodyDeclaration?: ClassBodyDeclarationCstNode[];
  RCurly: IToken[];
};

export interface ClassBodyDeclarationCstNode extends CstNode {
  name: "classBodyDeclaration";
  children: ClassBodyDeclarationCtx;
}

export type ClassBodyDeclarationCtx = {
  classMemberDeclaration?: ClassMemberDeclarationCstNode[];
  instanceInitializer?: InstanceInitializerCstNode[];
  staticInitializer?: StaticInitializerCstNode[];
  constructorDeclaration?: ConstructorDeclarationCstNode[];
};

export interface ClassMemberDeclarationCstNode extends CstNode {
  name: "classMemberDeclaration";
  children: ClassMemberDeclarationCtx;
}

export type ClassMemberDeclarationCtx = {
  fieldDeclaration?: FieldDeclarationCstNode[];
  methodDeclaration?: MethodDeclarationCstNode[];
  classDeclaration?: ClassDeclarationCstNode[];
  interfaceDeclaration?: InterfaceDeclarationCstNode[];
  Semicolon?: IToken[];
};

export interface FieldDeclarationCstNode extends CstNode {
  name: "fieldDeclaration";
  children: FieldDeclarationCtx;
}

export type FieldDeclarationCtx = {
  fieldModifier?: FieldModifierCstNode[];
  unannType: UnannTypeCstNode[];
  variableDeclaratorList: VariableDeclaratorListCstNode[];
  Semicolon: IToken[];
};

export interface FieldModifierCstNode extends CstNode {
  name: "fieldModifier";
  children: FieldModifierCtx;
}

export type FieldModifierCtx = {
  annotation?: AnnotationCstNode[];
  Public?: IToken[];
  Protected?: IToken[];
  Private?: IToken[];
  Static?: IToken[];
  Final?: IToken[];
  Transient?: IToken[];
  Volatile?: IToken[];
};

export interface VariableDeclaratorListCstNode extends CstNode {
  name: "variableDeclaratorList";
  children: VariableDeclaratorListCtx;
}

export type VariableDeclaratorListCtx = {
  variableDeclarator: VariableDeclaratorCstNode[];
  Comma?: IToken[];
};

export interface VariableDeclaratorCstNode extends CstNode {
  name: "variableDeclarator";
  children: VariableDeclaratorCtx;
}

export type VariableDeclaratorCtx = {
  variableDeclaratorId: VariableDeclaratorIdCstNode[];
  Equals?: IToken[];
  variableInitializer?: VariableInitializerCstNode[];
};

export interface VariableDeclaratorIdCstNode extends CstNode {
  name: "variableDeclaratorId";
  children: VariableDeclaratorIdCtx;
}

export type VariableDeclaratorIdCtx = {
  Identifier?: IToken[];
  dims?: DimsCstNode[];
  Underscore?: IToken[];
};

export interface VariableInitializerCstNode extends CstNode {
  name: "variableInitializer";
  children: VariableInitializerCtx;
}

export type VariableInitializerCtx = {
  expression?: ExpressionCstNode[];
  arrayInitializer?: ArrayInitializerCstNode[];
};

export interface UnannTypeCstNode extends CstNode {
  name: "unannType";
  children: UnannTypeCtx;
}

export type UnannTypeCtx = {
  unannPrimitiveTypeWithOptionalDimsSuffix?: UnannPrimitiveTypeWithOptionalDimsSuffixCstNode[];
  unannReferenceType?: UnannReferenceTypeCstNode[];
};

export interface UnannPrimitiveTypeWithOptionalDimsSuffixCstNode
  extends CstNode {
  name: "unannPrimitiveTypeWithOptionalDimsSuffix";
  children: UnannPrimitiveTypeWithOptionalDimsSuffixCtx;
}

export type UnannPrimitiveTypeWithOptionalDimsSuffixCtx = {
  unannPrimitiveType: UnannPrimitiveTypeCstNode[];
  dims?: DimsCstNode[];
};

export interface UnannPrimitiveTypeCstNode extends CstNode {
  name: "unannPrimitiveType";
  children: UnannPrimitiveTypeCtx;
}

export type UnannPrimitiveTypeCtx = {
  numericType?: NumericTypeCstNode[];
  Boolean?: IToken[];
};

export interface UnannReferenceTypeCstNode extends CstNode {
  name: "unannReferenceType";
  children: UnannReferenceTypeCtx;
}

export type UnannReferenceTypeCtx = {
  unannClassOrInterfaceType: UnannClassOrInterfaceTypeCstNode[];
  dims?: DimsCstNode[];
};

export interface UnannClassOrInterfaceTypeCstNode extends CstNode {
  name: "unannClassOrInterfaceType";
  children: UnannClassOrInterfaceTypeCtx;
}

export type UnannClassOrInterfaceTypeCtx = {
  unannClassType: UnannClassTypeCstNode[];
};

export interface UnannClassTypeCstNode extends CstNode {
  name: "unannClassType";
  children: UnannClassTypeCtx;
}

export type UnannClassTypeCtx = {
  Identifier: IToken[];
  typeArguments?: TypeArgumentsCstNode[];
  Dot?: IToken[];
  annotation?: AnnotationCstNode[];
};

export interface UnannInterfaceTypeCstNode extends CstNode {
  name: "unannInterfaceType";
  children: UnannInterfaceTypeCtx;
}

export type UnannInterfaceTypeCtx = {
  unannClassType: UnannClassTypeCstNode[];
};

export interface UnannTypeVariableCstNode extends CstNode {
  name: "unannTypeVariable";
  children: UnannTypeVariableCtx;
}

export type UnannTypeVariableCtx = {
  Identifier: IToken[];
};

export interface MethodDeclarationCstNode extends CstNode {
  name: "methodDeclaration";
  children: MethodDeclarationCtx;
}

export type MethodDeclarationCtx = {
  methodModifier?: MethodModifierCstNode[];
  methodHeader: MethodHeaderCstNode[];
  methodBody: MethodBodyCstNode[];
};

export interface MethodModifierCstNode extends CstNode {
  name: "methodModifier";
  children: MethodModifierCtx;
}

export type MethodModifierCtx = {
  annotation?: AnnotationCstNode[];
  Public?: IToken[];
  Protected?: IToken[];
  Private?: IToken[];
  Abstract?: IToken[];
  Static?: IToken[];
  Final?: IToken[];
  Synchronized?: IToken[];
  Native?: IToken[];
  Strictfp?: IToken[];
};

export interface MethodHeaderCstNode extends CstNode {
  name: "methodHeader";
  children: MethodHeaderCtx;
}

export type MethodHeaderCtx = {
  typeParameters?: TypeParametersCstNode[];
  annotation?: AnnotationCstNode[];
  result: ResultCstNode[];
  methodDeclarator: MethodDeclaratorCstNode[];
  throws?: ThrowsCstNode[];
};

export interface ResultCstNode extends CstNode {
  name: "result";
  children: ResultCtx;
}

export type ResultCtx = {
  unannType?: UnannTypeCstNode[];
  Void?: IToken[];
};

export interface MethodDeclaratorCstNode extends CstNode {
  name: "methodDeclarator";
  children: MethodDeclaratorCtx;
}

export type MethodDeclaratorCtx = {
  Identifier: IToken[];
  LBrace: IToken[];
  receiverParameter?: ReceiverParameterCstNode[];
  Comma?: IToken[];
  formalParameterList?: FormalParameterListCstNode[];
  RBrace: IToken[];
  dims?: DimsCstNode[];
};

export interface ReceiverParameterCstNode extends CstNode {
  name: "receiverParameter";
  children: ReceiverParameterCtx;
}

export type ReceiverParameterCtx = {
  annotation?: AnnotationCstNode[];
  unannType: UnannTypeCstNode[];
  Identifier?: IToken[];
  Dot?: IToken[];
  This: IToken[];
};

export interface FormalParameterListCstNode extends CstNode {
  name: "formalParameterList";
  children: FormalParameterListCtx;
}

export type FormalParameterListCtx = {
  formalParameter: FormalParameterCstNode[];
  Comma?: IToken[];
};

export interface FormalParameterCstNode extends CstNode {
  name: "formalParameter";
  children: FormalParameterCtx;
}

export type FormalParameterCtx = {
  variableParaRegularParameter?: VariableParaRegularParameterCstNode[];
  variableArityParameter?: VariableArityParameterCstNode[];
};

export interface VariableParaRegularParameterCstNode extends CstNode {
  name: "variableParaRegularParameter";
  children: VariableParaRegularParameterCtx;
}

export type VariableParaRegularParameterCtx = {
  variableModifier?: VariableModifierCstNode[];
  unannType: UnannTypeCstNode[];
  variableDeclaratorId: VariableDeclaratorIdCstNode[];
};

export interface VariableArityParameterCstNode extends CstNode {
  name: "variableArityParameter";
  children: VariableArityParameterCtx;
}

export type VariableArityParameterCtx = {
  variableModifier?: VariableModifierCstNode[];
  unannType: UnannTypeCstNode[];
  annotation?: AnnotationCstNode[];
  DotDotDot: IToken[];
  Identifier: IToken[];
};

export interface VariableModifierCstNode extends CstNode {
  name: "variableModifier";
  children: VariableModifierCtx;
}

export type VariableModifierCtx = {
  annotation?: AnnotationCstNode[];
  Final?: IToken[];
};

export interface ThrowsCstNode extends CstNode {
  name: "throws";
  children: ThrowsCtx;
}

export type ThrowsCtx = {
  Throws: IToken[];
  exceptionTypeList: ExceptionTypeListCstNode[];
};

export interface ExceptionTypeListCstNode extends CstNode {
  name: "exceptionTypeList";
  children: ExceptionTypeListCtx;
}

export type ExceptionTypeListCtx = {
  exceptionType: ExceptionTypeCstNode[];
  Comma?: IToken[];
};

export interface ExceptionTypeCstNode extends CstNode {
  name: "exceptionType";
  children: ExceptionTypeCtx;
}

export type ExceptionTypeCtx = {
  classType: ClassTypeCstNode[];
};

export interface MethodBodyCstNode extends CstNode {
  name: "methodBody";
  children: MethodBodyCtx;
}

export type MethodBodyCtx = {
  block?: BlockCstNode[];
  Semicolon?: IToken[];
};

export interface InstanceInitializerCstNode extends CstNode {
  name: "instanceInitializer";
  children: InstanceInitializerCtx;
}

export type InstanceInitializerCtx = {
  block: BlockCstNode[];
};

export interface StaticInitializerCstNode extends CstNode {
  name: "staticInitializer";
  children: StaticInitializerCtx;
}

export type StaticInitializerCtx = {
  Static: IToken[];
  block: BlockCstNode[];
};

export interface ConstructorDeclarationCstNode extends CstNode {
  name: "constructorDeclaration";
  children: ConstructorDeclarationCtx;
}

export type ConstructorDeclarationCtx = {
  constructorModifier?: ConstructorModifierCstNode[];
  constructorDeclarator: ConstructorDeclaratorCstNode[];
  throws?: ThrowsCstNode[];
  constructorBody: ConstructorBodyCstNode[];
};

export interface ConstructorModifierCstNode extends CstNode {
  name: "constructorModifier";
  children: ConstructorModifierCtx;
}

export type ConstructorModifierCtx = {
  annotation?: AnnotationCstNode[];
  Public?: IToken[];
  Protected?: IToken[];
  Private?: IToken[];
};

export interface ConstructorDeclaratorCstNode extends CstNode {
  name: "constructorDeclarator";
  children: ConstructorDeclaratorCtx;
}

export type ConstructorDeclaratorCtx = {
  typeParameters?: TypeParametersCstNode[];
  simpleTypeName: SimpleTypeNameCstNode[];
  LBrace: IToken[];
  receiverParameter?: ReceiverParameterCstNode[];
  Comma?: IToken[];
  formalParameterList?: FormalParameterListCstNode[];
  RBrace: IToken[];
};

export interface SimpleTypeNameCstNode extends CstNode {
  name: "simpleTypeName";
  children: SimpleTypeNameCtx;
}

export type SimpleTypeNameCtx = {
  Identifier: IToken[];
};

export interface ConstructorBodyCstNode extends CstNode {
  name: "constructorBody";
  children: ConstructorBodyCtx;
}

export type ConstructorBodyCtx = {
  LCurly: IToken[];
  explicitConstructorInvocation?: ExplicitConstructorInvocationCstNode[];
  blockStatements?: BlockStatementsCstNode[];
  RCurly: IToken[];
};

export interface ExplicitConstructorInvocationCstNode extends CstNode {
  name: "explicitConstructorInvocation";
  children: ExplicitConstructorInvocationCtx;
}

export type ExplicitConstructorInvocationCtx = {
  unqualifiedExplicitConstructorInvocation?: UnqualifiedExplicitConstructorInvocationCstNode[];
  qualifiedExplicitConstructorInvocation?: QualifiedExplicitConstructorInvocationCstNode[];
};

export interface UnqualifiedExplicitConstructorInvocationCstNode
  extends CstNode {
  name: "unqualifiedExplicitConstructorInvocation";
  children: UnqualifiedExplicitConstructorInvocationCtx;
}

export type UnqualifiedExplicitConstructorInvocationCtx = {
  typeArguments?: TypeArgumentsCstNode[];
  This?: IToken[];
  Super?: IToken[];
  LBrace: IToken[];
  argumentList?: ArgumentListCstNode[];
  RBrace: IToken[];
  Semicolon: IToken[];
};

export interface QualifiedExplicitConstructorInvocationCstNode extends CstNode {
  name: "qualifiedExplicitConstructorInvocation";
  children: QualifiedExplicitConstructorInvocationCtx;
}

export type QualifiedExplicitConstructorInvocationCtx = {
  expressionName: ExpressionNameCstNode[];
  Dot: IToken[];
  typeArguments?: TypeArgumentsCstNode[];
  Super: IToken[];
  LBrace: IToken[];
  argumentList?: ArgumentListCstNode[];
  RBrace: IToken[];
  Semicolon: IToken[];
};

export interface EnumDeclarationCstNode extends CstNode {
  name: "enumDeclaration";
  children: EnumDeclarationCtx;
}

export type EnumDeclarationCtx = {
  classModifier?: ClassModifierCstNode[];
  Enum: IToken[];
  typeIdentifier: TypeIdentifierCstNode[];
  superinterfaces?: SuperinterfacesCstNode[];
  enumBody: EnumBodyCstNode[];
};

export interface EnumBodyCstNode extends CstNode {
  name: "enumBody";
  children: EnumBodyCtx;
}

export type EnumBodyCtx = {
  LCurly: IToken[];
  enumConstantList?: EnumConstantListCstNode[];
  Comma?: IToken[];
  enumBodyDeclarations?: EnumBodyDeclarationsCstNode[];
  RCurly: IToken[];
};

export interface EnumConstantListCstNode extends CstNode {
  name: "enumConstantList";
  children: EnumConstantListCtx;
}

export type EnumConstantListCtx = {
  enumConstant: EnumConstantCstNode[];
  Comma?: IToken[];
};

export interface EnumConstantCstNode extends CstNode {
  name: "enumConstant";
  children: EnumConstantCtx;
}

export type EnumConstantCtx = {
  enumConstantModifier?: EnumConstantModifierCstNode[];
  Identifier: IToken[];
  LBrace?: IToken[];
  argumentList?: ArgumentListCstNode[];
  RBrace?: IToken[];
  classBody?: ClassBodyCstNode[];
};

export interface EnumConstantModifierCstNode extends CstNode {
  name: "enumConstantModifier";
  children: EnumConstantModifierCtx;
}

export type EnumConstantModifierCtx = {
  annotation: AnnotationCstNode[];
};

export interface EnumBodyDeclarationsCstNode extends CstNode {
  name: "enumBodyDeclarations";
  children: EnumBodyDeclarationsCtx;
}

export type EnumBodyDeclarationsCtx = {
  Semicolon: IToken[];
  classBodyDeclaration?: ClassBodyDeclarationCstNode[];
};

export interface RecordDeclarationCstNode extends CstNode {
  name: "recordDeclaration";
  children: RecordDeclarationCtx;
}

export type RecordDeclarationCtx = {
  Record: IToken[];
  typeIdentifier: TypeIdentifierCstNode[];
  typeParameters?: TypeParametersCstNode[];
  recordHeader: RecordHeaderCstNode[];
  superinterfaces?: SuperinterfacesCstNode[];
  recordBody: RecordBodyCstNode[];
};

export interface RecordHeaderCstNode extends CstNode {
  name: "recordHeader";
  children: RecordHeaderCtx;
}

export type RecordHeaderCtx = {
  LBrace: IToken[];
  recordComponentList?: RecordComponentListCstNode[];
  RBrace: IToken[];
};

export interface RecordComponentListCstNode extends CstNode {
  name: "recordComponentList";
  children: RecordComponentListCtx;
}

export type RecordComponentListCtx = {
  recordComponent: RecordComponentCstNode[];
  Comma?: IToken[];
};

export interface RecordComponentCstNode extends CstNode {
  name: "recordComponent";
  children: RecordComponentCtx;
}

export type RecordComponentCtx = {
  recordComponentModifier?: RecordComponentModifierCstNode[];
  unannType: UnannTypeCstNode[];
  Identifier?: IToken[];
  variableArityRecordComponent?: VariableArityRecordComponentCstNode[];
};

export interface VariableArityRecordComponentCstNode extends CstNode {
  name: "variableArityRecordComponent";
  children: VariableArityRecordComponentCtx;
}

export type VariableArityRecordComponentCtx = {
  annotation?: AnnotationCstNode[];
  DotDotDot: IToken[];
  Identifier: IToken[];
};

export interface RecordComponentModifierCstNode extends CstNode {
  name: "recordComponentModifier";
  children: RecordComponentModifierCtx;
}

export type RecordComponentModifierCtx = {
  annotation: AnnotationCstNode[];
};

export interface RecordBodyCstNode extends CstNode {
  name: "recordBody";
  children: RecordBodyCtx;
}

export type RecordBodyCtx = {
  LCurly: IToken[];
  recordBodyDeclaration?: RecordBodyDeclarationCstNode[];
  RCurly: IToken[];
};

export interface RecordBodyDeclarationCstNode extends CstNode {
  name: "recordBodyDeclaration";
  children: RecordBodyDeclarationCtx;
}

export type RecordBodyDeclarationCtx = {
  compactConstructorDeclaration?: CompactConstructorDeclarationCstNode[];
  classBodyDeclaration?: ClassBodyDeclarationCstNode[];
};

export interface CompactConstructorDeclarationCstNode extends CstNode {
  name: "compactConstructorDeclaration";
  children: CompactConstructorDeclarationCtx;
}

export type CompactConstructorDeclarationCtx = {
  constructorModifier?: ConstructorModifierCstNode[];
  simpleTypeName: SimpleTypeNameCstNode[];
  constructorBody: ConstructorBodyCstNode[];
};

export interface IsDimsCstNode extends CstNode {
  name: "isDims";
  children: IsDimsCtx;
}

export type IsDimsCtx = {
  At?: IToken[];
  typeName?: TypeNameCstNode[];
  LBrace?: IToken[];
  elementValuePairList?: ElementValuePairListCstNode[];
  elementValue?: ElementValueCstNode[];
  RBrace?: IToken[];
};

export interface CompilationUnitCstNode extends CstNode {
  name: "compilationUnit";
  children: CompilationUnitCtx;
}

export type AbstractOrdinaryCompilationUnitCtx = {
  ordinaryCompilationUnit: OrdinaryCompilationUnitCstNode[];
  EOF: IToken[];
};

export type AbstractModularCompilationUnitCtx = {
  modularCompilationUnit: OrdinaryCompilationUnitCstNode[];
  EOF: IToken[];
};

export type CompilationUnitCtx =
  | AbstractOrdinaryCompilationUnitCtx
  | AbstractModularCompilationUnitCtx;

export interface OrdinaryCompilationUnitCstNode extends CstNode {
  name: "ordinaryCompilationUnit";
  children: OrdinaryCompilationUnitCtx;
}

export type OrdinaryCompilationUnitCtx = {
  packageDeclaration?: PackageDeclarationCstNode[];
  importDeclaration?: ImportDeclarationCstNode[];
  typeDeclaration?: TypeDeclarationCstNode[];
};

export interface ModularCompilationUnitCstNode extends CstNode {
  name: "modularCompilationUnit";
  children: ModularCompilationUnitCtx;
}

export type ModularCompilationUnitCtx = {
  importDeclaration?: ImportDeclarationCstNode[];
  moduleDeclaration: ModuleDeclarationCstNode[];
};

export interface PackageDeclarationCstNode extends CstNode {
  name: "packageDeclaration";
  children: PackageDeclarationCtx;
}

export type PackageDeclarationCtx = {
  packageModifier?: PackageModifierCstNode[];
  Package: IToken[];
  Identifier: IToken[];
  Dot?: IToken[];
  Semicolon: IToken[];
};

export interface PackageModifierCstNode extends CstNode {
  name: "packageModifier";
  children: PackageModifierCtx;
}

export type PackageModifierCtx = {
  annotation: AnnotationCstNode[];
};

export interface ImportDeclarationCstNode extends CstNode {
  name: "importDeclaration";
  children: ImportDeclarationCtx;
}

export type ImportDeclarationCtx = {
  Import?: IToken[];
  Static?: IToken[];
  packageOrTypeName?: PackageOrTypeNameCstNode[];
  Dot?: IToken[];
  Star?: IToken[];
  Semicolon?: IToken[];
  emptyStatement?: EmptyStatementCstNode[];
};

export interface TypeDeclarationCstNode extends CstNode {
  name: "typeDeclaration";
  children: TypeDeclarationCtx;
}

export type TypeDeclarationCtx = {
  Semicolon?: IToken[];
  classDeclaration?: ClassDeclarationCstNode[];
  interfaceDeclaration?: InterfaceDeclarationCstNode[];
  fieldDeclaration?: FieldDeclarationCstNode[];
  methodDeclaration?: MethodDeclarationCstNode[];
};

export interface ModuleDeclarationCstNode extends CstNode {
  name: "moduleDeclaration";
  children: ModuleDeclarationCtx;
}

export type ModuleDeclarationCtx = {
  annotation?: AnnotationCstNode[];
  Open?: IToken[];
  Module: IToken[];
  Identifier: IToken[];
  Dot?: IToken[];
  LCurly: IToken[];
  moduleDirective?: ModuleDirectiveCstNode[];
  RCurly: IToken[];
};

export interface ModuleDirectiveCstNode extends CstNode {
  name: "moduleDirective";
  children: ModuleDirectiveCtx;
}

export type ModuleDirectiveCtx = {
  requiresModuleDirective?: RequiresModuleDirectiveCstNode[];
  exportsModuleDirective?: ExportsModuleDirectiveCstNode[];
  opensModuleDirective?: OpensModuleDirectiveCstNode[];
  usesModuleDirective?: UsesModuleDirectiveCstNode[];
  providesModuleDirective?: ProvidesModuleDirectiveCstNode[];
};

export interface RequiresModuleDirectiveCstNode extends CstNode {
  name: "requiresModuleDirective";
  children: RequiresModuleDirectiveCtx;
}

export type RequiresModuleDirectiveCtx = {
  Requires: IToken[];
  requiresModifier?: RequiresModifierCstNode[];
  moduleName: ModuleNameCstNode[];
  Semicolon: IToken[];
};

export interface ExportsModuleDirectiveCstNode extends CstNode {
  name: "exportsModuleDirective";
  children: ExportsModuleDirectiveCtx;
}

export type ExportsModuleDirectiveCtx = {
  Exports: IToken[];
  packageName: PackageNameCstNode[];
  To?: IToken[];
  moduleName?: ModuleNameCstNode[];
  Comma?: IToken[];
  Semicolon: IToken[];
};

export interface OpensModuleDirectiveCstNode extends CstNode {
  name: "opensModuleDirective";
  children: OpensModuleDirectiveCtx;
}

export type OpensModuleDirectiveCtx = {
  Opens: IToken[];
  packageName: PackageNameCstNode[];
  To?: IToken[];
  moduleName?: ModuleNameCstNode[];
  Comma?: IToken[];
  Semicolon: IToken[];
};

export interface UsesModuleDirectiveCstNode extends CstNode {
  name: "usesModuleDirective";
  children: UsesModuleDirectiveCtx;
}

export type UsesModuleDirectiveCtx = {
  Uses: IToken[];
  typeName: TypeNameCstNode[];
  Semicolon: IToken[];
};

export interface ProvidesModuleDirectiveCstNode extends CstNode {
  name: "providesModuleDirective";
  children: ProvidesModuleDirectiveCtx;
}

export type ProvidesModuleDirectiveCtx = {
  Provides: IToken[];
  typeName: TypeNameCstNode[];
  With: IToken[];
  Comma?: IToken[];
  Semicolon: IToken[];
};

export interface RequiresModifierCstNode extends CstNode {
  name: "requiresModifier";
  children: RequiresModifierCtx;
}

export type RequiresModifierCtx = {
  Transitive?: IToken[];
  Static?: IToken[];
};

export interface InterfaceDeclarationCstNode extends CstNode {
  name: "interfaceDeclaration";
  children: InterfaceDeclarationCtx;
}

export type InterfaceDeclarationCtx = {
  interfaceModifier?: InterfaceModifierCstNode[];
  normalInterfaceDeclaration?: NormalInterfaceDeclarationCstNode[];
  annotationTypeDeclaration?: AnnotationTypeDeclarationCstNode[];
};

export interface NormalInterfaceDeclarationCstNode extends CstNode {
  name: "normalInterfaceDeclaration";
  children: NormalInterfaceDeclarationCtx;
}

export type NormalInterfaceDeclarationCtx = {
  Interface: IToken[];
  typeIdentifier: TypeIdentifierCstNode[];
  typeParameters?: TypeParametersCstNode[];
  extendsInterfaces?: ExtendsInterfacesCstNode[];
  interfacePermits?: InterfacePermitsCstNode[];
  interfaceBody: InterfaceBodyCstNode[];
};

export interface InterfaceModifierCstNode extends CstNode {
  name: "interfaceModifier";
  children: InterfaceModifierCtx;
}

export type InterfaceModifierCtx = {
  annotation?: AnnotationCstNode[];
  Public?: IToken[];
  Protected?: IToken[];
  Private?: IToken[];
  Abstract?: IToken[];
  Static?: IToken[];
  Sealed?: IToken[];
  NonSealed?: IToken[];
  Strictfp?: IToken[];
};

export interface ExtendsInterfacesCstNode extends CstNode {
  name: "extendsInterfaces";
  children: ExtendsInterfacesCtx;
}

export type ExtendsInterfacesCtx = {
  Extends: IToken[];
  interfaceTypeList: InterfaceTypeListCstNode[];
};

export interface InterfacePermitsCstNode extends CstNode {
  name: "interfacePermits";
  children: InterfacePermitsCtx;
}

export type InterfacePermitsCtx = {
  Permits: IToken[];
  typeName: TypeNameCstNode[];
  Comma?: IToken[];
};

export interface InterfaceBodyCstNode extends CstNode {
  name: "interfaceBody";
  children: InterfaceBodyCtx;
}

export type InterfaceBodyCtx = {
  LCurly: IToken[];
  interfaceMemberDeclaration?: InterfaceMemberDeclarationCstNode[];
  RCurly: IToken[];
};

export interface InterfaceMemberDeclarationCstNode extends CstNode {
  name: "interfaceMemberDeclaration";
  children: InterfaceMemberDeclarationCtx;
}

export type InterfaceMemberDeclarationCtx = {
  constantDeclaration?: ConstantDeclarationCstNode[];
  interfaceMethodDeclaration?: InterfaceMethodDeclarationCstNode[];
  classDeclaration?: ClassDeclarationCstNode[];
  interfaceDeclaration?: InterfaceDeclarationCstNode[];
  Semicolon?: IToken[];
};

export interface ConstantDeclarationCstNode extends CstNode {
  name: "constantDeclaration";
  children: ConstantDeclarationCtx;
}

export type ConstantDeclarationCtx = {
  constantModifier?: ConstantModifierCstNode[];
  unannType: UnannTypeCstNode[];
  variableDeclaratorList: VariableDeclaratorListCstNode[];
  Semicolon: IToken[];
};

export interface ConstantModifierCstNode extends CstNode {
  name: "constantModifier";
  children: ConstantModifierCtx;
}

export type ConstantModifierCtx = {
  annotation?: AnnotationCstNode[];
  Public?: IToken[];
  Static?: IToken[];
  Final?: IToken[];
};

export interface InterfaceMethodDeclarationCstNode extends CstNode {
  name: "interfaceMethodDeclaration";
  children: InterfaceMethodDeclarationCtx;
}

export type InterfaceMethodDeclarationCtx = {
  interfaceMethodModifier?: InterfaceMethodModifierCstNode[];
  methodHeader: MethodHeaderCstNode[];
  methodBody: MethodBodyCstNode[];
};

export interface InterfaceMethodModifierCstNode extends CstNode {
  name: "interfaceMethodModifier";
  children: InterfaceMethodModifierCtx;
}

export type InterfaceMethodModifierCtx = {
  annotation?: AnnotationCstNode[];
  Public?: IToken[];
  Private?: IToken[];
  Abstract?: IToken[];
  Default?: IToken[];
  Static?: IToken[];
  Strictfp?: IToken[];
};

export interface AnnotationTypeDeclarationCstNode extends CstNode {
  name: "annotationTypeDeclaration";
  children: AnnotationTypeDeclarationCtx;
}

export type AnnotationTypeDeclarationCtx = {
  At: IToken[];
  Interface: IToken[];
  typeIdentifier: TypeIdentifierCstNode[];
  annotationTypeBody: AnnotationTypeBodyCstNode[];
};

export interface AnnotationTypeBodyCstNode extends CstNode {
  name: "annotationTypeBody";
  children: AnnotationTypeBodyCtx;
}

export type AnnotationTypeBodyCtx = {
  LCurly: IToken[];
  annotationTypeMemberDeclaration?: AnnotationTypeMemberDeclarationCstNode[];
  RCurly: IToken[];
};

export interface AnnotationTypeMemberDeclarationCstNode extends CstNode {
  name: "annotationTypeMemberDeclaration";
  children: AnnotationTypeMemberDeclarationCtx;
}

export type AnnotationTypeMemberDeclarationCtx = {
  annotationTypeElementDeclaration?: AnnotationTypeElementDeclarationCstNode[];
  constantDeclaration?: ConstantDeclarationCstNode[];
  classDeclaration?: ClassDeclarationCstNode[];
  interfaceDeclaration?: InterfaceDeclarationCstNode[];
  Semicolon?: IToken[];
};

export interface AnnotationTypeElementDeclarationCstNode extends CstNode {
  name: "annotationTypeElementDeclaration";
  children: AnnotationTypeElementDeclarationCtx;
}

export type AnnotationTypeElementDeclarationCtx = {
  annotationTypeElementModifier?: AnnotationTypeElementModifierCstNode[];
  unannType: UnannTypeCstNode[];
  Identifier: IToken[];
  LBrace: IToken[];
  RBrace: IToken[];
  dims?: DimsCstNode[];
  defaultValue?: DefaultValueCstNode[];
  Semicolon: IToken[];
};

export interface AnnotationTypeElementModifierCstNode extends CstNode {
  name: "annotationTypeElementModifier";
  children: AnnotationTypeElementModifierCtx;
}

export type AnnotationTypeElementModifierCtx = {
  annotation?: AnnotationCstNode[];
  Public?: IToken[];
  Abstract?: IToken[];
};

export interface DefaultValueCstNode extends CstNode {
  name: "defaultValue";
  children: DefaultValueCtx;
}

export type DefaultValueCtx = {
  Default: IToken[];
  elementValue: ElementValueCstNode[];
};

export interface AnnotationCstNode extends CstNode {
  name: "annotation";
  children: AnnotationCtx;
}

export type AnnotationCtx = {
  At: IToken[];
  typeName: TypeNameCstNode[];
  LBrace?: IToken[];
  elementValuePairList?: ElementValuePairListCstNode[];
  elementValue?: ElementValueCstNode[];
  RBrace?: IToken[];
};

export interface ElementValuePairListCstNode extends CstNode {
  name: "elementValuePairList";
  children: ElementValuePairListCtx;
}

export type ElementValuePairListCtx = {
  elementValuePair: ElementValuePairCstNode[];
  Comma?: IToken[];
};

export interface ElementValuePairCstNode extends CstNode {
  name: "elementValuePair";
  children: ElementValuePairCtx;
}

export type ElementValuePairCtx = {
  Identifier: IToken[];
  Equals: IToken[];
  elementValue: ElementValueCstNode[];
};

export interface ElementValueCstNode extends CstNode {
  name: "elementValue";
  children: ElementValueCtx;
}

export type ElementValueCtx = {
  expression?: ExpressionCstNode[];
  elementValueArrayInitializer?: ElementValueArrayInitializerCstNode[];
  annotation?: AnnotationCstNode[];
};

export interface ElementValueArrayInitializerCstNode extends CstNode {
  name: "elementValueArrayInitializer";
  children: ElementValueArrayInitializerCtx;
}

export type ElementValueArrayInitializerCtx = {
  LCurly: IToken[];
  elementValueList?: ElementValueListCstNode[];
  Comma?: IToken[];
  RCurly: IToken[];
};

export interface ElementValueListCstNode extends CstNode {
  name: "elementValueList";
  children: ElementValueListCtx;
}

export type ElementValueListCtx = {
  elementValue: ElementValueCstNode[];
  Comma?: IToken[];
};

export interface ArrayInitializerCstNode extends CstNode {
  name: "arrayInitializer";
  children: ArrayInitializerCtx;
}

export type ArrayInitializerCtx = {
  LCurly: IToken[];
  variableInitializerList?: VariableInitializerListCstNode[];
  Comma?: IToken[];
  RCurly: IToken[];
};

export interface VariableInitializerListCstNode extends CstNode {
  name: "variableInitializerList";
  children: VariableInitializerListCtx;
}

export type VariableInitializerListCtx = {
  variableInitializer: VariableInitializerCstNode[];
  Comma?: IToken[];
};

export interface BlockCstNode extends CstNode {
  name: "block";
  children: BlockCtx;
}

export type BlockCtx = {
  LCurly: IToken[];
  blockStatements?: BlockStatementsCstNode[];
  RCurly: IToken[];
};

export interface BlockStatementsCstNode extends CstNode {
  name: "blockStatements";
  children: BlockStatementsCtx;
}

export type BlockStatementsCtx = {
  blockStatement: BlockStatementCstNode[];
};

export interface BlockStatementCstNode extends CstNode {
  name: "blockStatement";
  children: BlockStatementCtx;
}

export type BlockStatementCtx = {
  localVariableDeclarationStatement?: LocalVariableDeclarationStatementCstNode[];
  classDeclaration?: ClassDeclarationCstNode[];
  interfaceDeclaration?: InterfaceDeclarationCstNode[];
  statement?: StatementCstNode[];
};

export interface LocalVariableDeclarationStatementCstNode extends CstNode {
  name: "localVariableDeclarationStatement";
  children: LocalVariableDeclarationStatementCtx;
}

export type LocalVariableDeclarationStatementCtx = {
  localVariableDeclaration: LocalVariableDeclarationCstNode[];
  Semicolon: IToken[];
};

export interface LocalVariableDeclarationCstNode extends CstNode {
  name: "localVariableDeclaration";
  children: LocalVariableDeclarationCtx;
}

export type LocalVariableDeclarationCtx = {
  variableModifier?: VariableModifierCstNode[];
  localVariableType: LocalVariableTypeCstNode[];
  variableDeclaratorList: VariableDeclaratorListCstNode[];
};

export interface LocalVariableTypeCstNode extends CstNode {
  name: "localVariableType";
  children: LocalVariableTypeCtx;
}

export type LocalVariableTypeCtx = {
  unannType?: UnannTypeCstNode[];
  Var?: IToken[];
};

export interface StatementCstNode extends CstNode {
  name: "statement";
  children: StatementCtx;
}

export type StatementCtx = {
  statementWithoutTrailingSubstatement?: StatementWithoutTrailingSubstatementCstNode[];
  labeledStatement?: LabeledStatementCstNode[];
  ifStatement?: IfStatementCstNode[];
  whileStatement?: WhileStatementCstNode[];
  forStatement?: ForStatementCstNode[];
};

export interface StatementWithoutTrailingSubstatementCstNode extends CstNode {
  name: "statementWithoutTrailingSubstatement";
  children: StatementWithoutTrailingSubstatementCtx;
}

export type StatementWithoutTrailingSubstatementCtx = {
  block?: BlockCstNode[];
  yieldStatement?: YieldStatementCstNode[];
  emptyStatement?: EmptyStatementCstNode[];
  expressionStatement?: ExpressionStatementCstNode[];
  assertStatement?: AssertStatementCstNode[];
  switchStatement?: SwitchStatementCstNode[];
  doStatement?: DoStatementCstNode[];
  breakStatement?: BreakStatementCstNode[];
  continueStatement?: ContinueStatementCstNode[];
  returnStatement?: ReturnStatementCstNode[];
  synchronizedStatement?: SynchronizedStatementCstNode[];
  throwStatement?: ThrowStatementCstNode[];
  tryStatement?: TryStatementCstNode[];
};

export interface EmptyStatementCstNode extends CstNode {
  name: "emptyStatement";
  children: EmptyStatementCtx;
}

export type EmptyStatementCtx = {
  Semicolon: IToken[];
};

export interface LabeledStatementCstNode extends CstNode {
  name: "labeledStatement";
  children: LabeledStatementCtx;
}

export type LabeledStatementCtx = {
  Identifier: IToken[];
  Colon: IToken[];
  statement: StatementCstNode[];
};

export interface ExpressionStatementCstNode extends CstNode {
  name: "expressionStatement";
  children: ExpressionStatementCtx;
}

export type ExpressionStatementCtx = {
  statementExpression: StatementExpressionCstNode[];
  Semicolon: IToken[];
};

export interface StatementExpressionCstNode extends CstNode {
  name: "statementExpression";
  children: StatementExpressionCtx;
}

export type StatementExpressionCtx = {
  expression: ExpressionCstNode[];
};

export interface IfStatementCstNode extends CstNode {
  name: "ifStatement";
  children: IfStatementCtx;
}

export type IfStatementCtx = {
  If: IToken[];
  LBrace: IToken[];
  expression: ExpressionCstNode[];
  RBrace: IToken[];
  statement: StatementCstNode[];
  Else?: IToken[];
};

export interface AssertStatementCstNode extends CstNode {
  name: "assertStatement";
  children: AssertStatementCtx;
}

export type AssertStatementCtx = {
  Assert: IToken[];
  expression: ExpressionCstNode[];
  Colon?: IToken[];
  Semicolon: IToken[];
};

export interface SwitchStatementCstNode extends CstNode {
  name: "switchStatement";
  children: SwitchStatementCtx;
}

export type SwitchStatementCtx = {
  Switch: IToken[];
  LBrace: IToken[];
  expression: ExpressionCstNode[];
  RBrace: IToken[];
  switchBlock: SwitchBlockCstNode[];
};

export interface SwitchBlockCstNode extends CstNode {
  name: "switchBlock";
  children: SwitchBlockCtx;
}

export type SwitchBlockCtx = {
  LCurly: IToken[];
  switchBlockStatementGroup?: SwitchBlockStatementGroupCstNode[];
  switchRule?: SwitchRuleCstNode[];
  RCurly: IToken[];
};

export interface SwitchBlockStatementGroupCstNode extends CstNode {
  name: "switchBlockStatementGroup";
  children: SwitchBlockStatementGroupCtx;
}

export type SwitchBlockStatementGroupCtx = {
  switchLabel: SwitchLabelCstNode[];
  Colon: IToken[];
  blockStatements?: BlockStatementsCstNode[];
};

export interface SwitchLabelCstNode extends CstNode {
  name: "switchLabel";
  children: SwitchLabelCtx;
}

export type SwitchLabelCtx = {
  Case?: IToken[];
  Comma?: IToken[];
  Null?: IToken[];
  Default?: IToken[];
  pattern?: PatternCstNode[];
  guard?: GuardCstNode[];
  caseConstant?: CaseConstantCstNode[];
};

export interface SwitchRuleCstNode extends CstNode {
  name: "switchRule";
  children: SwitchRuleCtx;
}

export type SwitchRuleCtx = {
  switchLabel: SwitchLabelCstNode[];
  Arrow: IToken[];
  throwStatement?: ThrowStatementCstNode[];
  block?: BlockCstNode[];
  expression?: ExpressionCstNode[];
  Semicolon?: IToken[];
};

export interface CaseConstantCstNode extends CstNode {
  name: "caseConstant";
  children: CaseConstantCtx;
}

export type CaseConstantCtx = {
  ternaryExpression: TernaryExpressionCstNode[];
};

export interface WhileStatementCstNode extends CstNode {
  name: "whileStatement";
  children: WhileStatementCtx;
}

export type WhileStatementCtx = {
  While: IToken[];
  LBrace: IToken[];
  expression: ExpressionCstNode[];
  RBrace: IToken[];
  statement: StatementCstNode[];
};

export interface DoStatementCstNode extends CstNode {
  name: "doStatement";
  children: DoStatementCtx;
}

export type DoStatementCtx = {
  Do: IToken[];
  statement: StatementCstNode[];
  While: IToken[];
  LBrace: IToken[];
  expression: ExpressionCstNode[];
  RBrace: IToken[];
  Semicolon: IToken[];
};

export interface ForStatementCstNode extends CstNode {
  name: "forStatement";
  children: ForStatementCtx;
}

export type ForStatementCtx = {
  basicForStatement?: BasicForStatementCstNode[];
  enhancedForStatement?: EnhancedForStatementCstNode[];
};

export interface BasicForStatementCstNode extends CstNode {
  name: "basicForStatement";
  children: BasicForStatementCtx;
}

export type BasicForStatementCtx = {
  For: IToken[];
  LBrace: IToken[];
  forInit?: ForInitCstNode[];
  Semicolon: IToken[];
  expression?: ExpressionCstNode[];
  forUpdate?: ForUpdateCstNode[];
  RBrace: IToken[];
  statement: StatementCstNode[];
};

export interface ForInitCstNode extends CstNode {
  name: "forInit";
  children: ForInitCtx;
}

export type ForInitCtx = {
  localVariableDeclaration?: LocalVariableDeclarationCstNode[];
  statementExpressionList?: StatementExpressionListCstNode[];
};

export interface ForUpdateCstNode extends CstNode {
  name: "forUpdate";
  children: ForUpdateCtx;
}

export type ForUpdateCtx = {
  statementExpressionList: StatementExpressionListCstNode[];
};

export interface StatementExpressionListCstNode extends CstNode {
  name: "statementExpressionList";
  children: StatementExpressionListCtx;
}

export type StatementExpressionListCtx = {
  statementExpression: StatementExpressionCstNode[];
  Comma?: IToken[];
};

export interface EnhancedForStatementCstNode extends CstNode {
  name: "enhancedForStatement";
  children: EnhancedForStatementCtx;
}

export type EnhancedForStatementCtx = {
  For: IToken[];
  LBrace: IToken[];
  variableModifier?: VariableModifierCstNode[];
  localVariableType: LocalVariableTypeCstNode[];
  variableDeclaratorId: VariableDeclaratorIdCstNode[];
  Colon: IToken[];
  expression: ExpressionCstNode[];
  RBrace: IToken[];
  statement: StatementCstNode[];
};

export interface BreakStatementCstNode extends CstNode {
  name: "breakStatement";
  children: BreakStatementCtx;
}

export type BreakStatementCtx = {
  Break: IToken[];
  Identifier?: IToken[];
  Semicolon: IToken[];
};

export interface ContinueStatementCstNode extends CstNode {
  name: "continueStatement";
  children: ContinueStatementCtx;
}

export type ContinueStatementCtx = {
  Continue: IToken[];
  Identifier?: IToken[];
  Semicolon: IToken[];
};

export interface ReturnStatementCstNode extends CstNode {
  name: "returnStatement";
  children: ReturnStatementCtx;
}

export type ReturnStatementCtx = {
  Return: IToken[];
  expression?: ExpressionCstNode[];
  Semicolon: IToken[];
};

export interface ThrowStatementCstNode extends CstNode {
  name: "throwStatement";
  children: ThrowStatementCtx;
}

export type ThrowStatementCtx = {
  Throw: IToken[];
  expression: ExpressionCstNode[];
  Semicolon: IToken[];
};

export interface SynchronizedStatementCstNode extends CstNode {
  name: "synchronizedStatement";
  children: SynchronizedStatementCtx;
}

export type SynchronizedStatementCtx = {
  Synchronized: IToken[];
  LBrace: IToken[];
  expression: ExpressionCstNode[];
  RBrace: IToken[];
  block: BlockCstNode[];
};

export interface TryStatementCstNode extends CstNode {
  name: "tryStatement";
  children: TryStatementCtx;
}

export type TryStatementCtx = {
  Try?: IToken[];
  block?: BlockCstNode[];
  catches?: CatchesCstNode[];
  finally?: FinallyCstNode[];
  tryWithResourcesStatement?: TryWithResourcesStatementCstNode[];
};

export interface CatchesCstNode extends CstNode {
  name: "catches";
  children: CatchesCtx;
}

export type CatchesCtx = {
  catchClause: CatchClauseCstNode[];
};

export interface CatchClauseCstNode extends CstNode {
  name: "catchClause";
  children: CatchClauseCtx;
}

export type CatchClauseCtx = {
  Catch: IToken[];
  LBrace: IToken[];
  catchFormalParameter: CatchFormalParameterCstNode[];
  RBrace: IToken[];
  block: BlockCstNode[];
};

export interface CatchFormalParameterCstNode extends CstNode {
  name: "catchFormalParameter";
  children: CatchFormalParameterCtx;
}

export type CatchFormalParameterCtx = {
  variableModifier?: VariableModifierCstNode[];
  catchType: CatchTypeCstNode[];
  variableDeclaratorId: VariableDeclaratorIdCstNode[];
};

export interface CatchTypeCstNode extends CstNode {
  name: "catchType";
  children: CatchTypeCtx;
}

export type CatchTypeCtx = {
  unannClassType: UnannClassTypeCstNode[];
  Or?: IToken[];
  classType?: ClassTypeCstNode[];
};

export interface FinallyCstNode extends CstNode {
  name: "finally";
  children: FinallyCtx;
}

export type FinallyCtx = {
  Finally: IToken[];
  block: BlockCstNode[];
};

export interface TryWithResourcesStatementCstNode extends CstNode {
  name: "tryWithResourcesStatement";
  children: TryWithResourcesStatementCtx;
}

export type TryWithResourcesStatementCtx = {
  Try: IToken[];
  resourceSpecification: ResourceSpecificationCstNode[];
  block: BlockCstNode[];
  catches?: CatchesCstNode[];
  finally?: FinallyCstNode[];
};

export interface ResourceSpecificationCstNode extends CstNode {
  name: "resourceSpecification";
  children: ResourceSpecificationCtx;
}

export type ResourceSpecificationCtx = {
  LBrace: IToken[];
  resourceList: ResourceListCstNode[];
  Semicolon?: IToken[];
  RBrace: IToken[];
};

export interface ResourceListCstNode extends CstNode {
  name: "resourceList";
  children: ResourceListCtx;
}

export type ResourceListCtx = {
  resource: ResourceCstNode[];
  Semicolon?: IToken[];
};

export interface ResourceCstNode extends CstNode {
  name: "resource";
  children: ResourceCtx;
}

export type ResourceCtx = {
  localVariableDeclaration?: LocalVariableDeclarationCstNode[];
  variableAccess?: VariableAccessCstNode[];
};

export interface YieldStatementCstNode extends CstNode {
  name: "yieldStatement";
  children: YieldStatementCtx;
}

export type YieldStatementCtx = {
  Yield: IToken[];
  expression: ExpressionCstNode[];
  Semicolon: IToken[];
};

export interface VariableAccessCstNode extends CstNode {
  name: "variableAccess";
  children: VariableAccessCtx;
}

export type VariableAccessCtx = {
  primary: PrimaryCstNode[];
};

export interface ExpressionCstNode extends CstNode {
  name: "expression";
  children: ExpressionCtx;
}

export type ExpressionCtx = {
  lambdaExpression?: LambdaExpressionCstNode[];
  ternaryExpression?: TernaryExpressionCstNode[];
};

export interface LambdaExpressionCstNode extends CstNode {
  name: "lambdaExpression";
  children: LambdaExpressionCtx;
}

export type LambdaExpressionCtx = {
  lambdaParameters: LambdaParametersCstNode[];
  Arrow: IToken[];
  lambdaBody: LambdaBodyCstNode[];
};

export interface LambdaParametersCstNode extends CstNode {
  name: "lambdaParameters";
  children: LambdaParametersCtx;
}

export type LambdaParametersCtx = {
  lambdaParametersWithBraces?: LambdaParametersWithBracesCstNode[];
  Identifier?: IToken[];
  Underscore?: IToken[];
};

export interface LambdaParametersWithBracesCstNode extends CstNode {
  name: "lambdaParametersWithBraces";
  children: LambdaParametersWithBracesCtx;
}

export type LambdaParametersWithBracesCtx = {
  LBrace: IToken[];
  lambdaParameterList?: LambdaParameterListCstNode[];
  RBrace: IToken[];
};

export interface LambdaParameterListCstNode extends CstNode {
  name: "lambdaParameterList";
  children: LambdaParameterListCtx;
}

export type LambdaParameterListCtx = {
  inferredLambdaParameterList?: InferredLambdaParameterListCstNode[];
  explicitLambdaParameterList?: ExplicitLambdaParameterListCstNode[];
};

export interface InferredLambdaParameterListCstNode extends CstNode {
  name: "inferredLambdaParameterList";
  children: InferredLambdaParameterListCtx;
}

export type InferredLambdaParameterListCtx = {
  Identifier: IToken[];
  Comma?: IToken[];
};

export interface ExplicitLambdaParameterListCstNode extends CstNode {
  name: "explicitLambdaParameterList";
  children: ExplicitLambdaParameterListCtx;
}

export type ExplicitLambdaParameterListCtx = {
  lambdaParameter: LambdaParameterCstNode[];
  Comma?: IToken[];
};

export interface LambdaParameterCstNode extends CstNode {
  name: "lambdaParameter";
  children: LambdaParameterCtx;
}

export type LambdaParameterCtx = {
  regularLambdaParameter?: RegularLambdaParameterCstNode[];
  variableArityParameter?: VariableArityParameterCstNode[];
};

export interface RegularLambdaParameterCstNode extends CstNode {
  name: "regularLambdaParameter";
  children: RegularLambdaParameterCtx;
}

export type RegularLambdaParameterCtx = {
  variableModifier?: VariableModifierCstNode[];
  lambdaParameterType: LambdaParameterTypeCstNode[];
  variableDeclaratorId: VariableDeclaratorIdCstNode[];
};

export interface LambdaParameterTypeCstNode extends CstNode {
  name: "lambdaParameterType";
  children: LambdaParameterTypeCtx;
}

export type LambdaParameterTypeCtx = {
  unannType?: UnannTypeCstNode[];
  Var?: IToken[];
};

export interface LambdaBodyCstNode extends CstNode {
  name: "lambdaBody";
  children: LambdaBodyCtx;
}

export type LambdaBodyCtx = {
  expression?: ExpressionCstNode[];
  block?: BlockCstNode[];
};

export interface TernaryExpressionCstNode extends CstNode {
  name: "ternaryExpression";
  children: TernaryExpressionCtx;
}

export type TernaryExpressionCtx = {
  binaryExpression: BinaryExpressionCstNode[];
  QuestionMark?: IToken[];
  expression?: ExpressionCstNode[];
  Colon?: IToken[];
};

export interface BinaryExpressionCstNode extends CstNode {
  name: "binaryExpression";
  children: BinaryExpressionCtx;
}

export type BinaryExpressionCtx = {
  unaryExpression: UnaryExpressionCstNode[];
  Instanceof?: IToken[];
  pattern?: PatternCstNode[];
  referenceType?: ReferenceTypeCstNode[];
  AssignmentOperator?: IToken[];
  expression?: ExpressionCstNode[];
  Less?: IToken[];
  Greater?: IToken[];
  BinaryOperator?: IToken[];
};

export interface UnaryExpressionCstNode extends CstNode {
  name: "unaryExpression";
  children: UnaryExpressionCtx;
}

export type UnaryExpressionCtx = {
  UnaryPrefixOperator?: IToken[];
  primary: PrimaryCstNode[];
  UnarySuffixOperator?: IToken[];
};

export interface UnaryExpressionNotPlusMinusCstNode extends CstNode {
  name: "unaryExpressionNotPlusMinus";
  children: UnaryExpressionNotPlusMinusCtx;
}

export type UnaryExpressionNotPlusMinusCtx = {
  UnaryPrefixOperatorNotPlusMinus?: IToken[];
  primary: PrimaryCstNode[];
  UnarySuffixOperator?: IToken[];
};

export interface PrimaryCstNode extends CstNode {
  name: "primary";
  children: PrimaryCtx;
}

export type PrimaryCtx = {
  primaryPrefix: PrimaryPrefixCstNode[];
  primarySuffix?: PrimarySuffixCstNode[];
};

export interface PrimaryPrefixCstNode extends CstNode {
  name: "primaryPrefix";
  children: PrimaryPrefixCtx;
}

export type PrimaryPrefixCtx = {
  literal?: LiteralCstNode[];
  This?: IToken[];
  Void?: IToken[];
  unannPrimitiveTypeWithOptionalDimsSuffix?: UnannPrimitiveTypeWithOptionalDimsSuffixCstNode[];
  fqnOrRefType?: FqnOrRefTypeCstNode[];
  castExpression?: CastExpressionCstNode[];
  parenthesisExpression?: ParenthesisExpressionCstNode[];
  newExpression?: NewExpressionCstNode[];
  switchStatement?: SwitchStatementCstNode[];
};

export interface PrimarySuffixCstNode extends CstNode {
  name: "primarySuffix";
  children: PrimarySuffixCtx;
}

export type PrimarySuffixCtx = {
  Dot?: IToken[];
  This?: IToken[];
  unqualifiedClassInstanceCreationExpression?: UnqualifiedClassInstanceCreationExpressionCstNode[];
  typeArguments?: TypeArgumentsCstNode[];
  Identifier?: IToken[];
  templateArgument?: TemplateArgumentCstNode[];
  methodInvocationSuffix?: MethodInvocationSuffixCstNode[];
  classLiteralSuffix?: ClassLiteralSuffixCstNode[];
  arrayAccessSuffix?: ArrayAccessSuffixCstNode[];
  methodReferenceSuffix?: MethodReferenceSuffixCstNode[];
};

export interface FqnOrRefTypeCstNode extends CstNode {
  name: "fqnOrRefType";
  children: FqnOrRefTypeCtx;
}

export type FqnOrRefTypeCtx = {
  fqnOrRefTypePartFirst: FqnOrRefTypePartFirstCstNode[];
  Dot?: IToken[];
  fqnOrRefTypePartRest?: FqnOrRefTypePartRestCstNode[];
  dims?: DimsCstNode[];
};

export interface FqnOrRefTypePartRestCstNode extends CstNode {
  name: "fqnOrRefTypePartRest";
  children: FqnOrRefTypePartRestCtx;
}

export type FqnOrRefTypePartRestCtx = {
  annotation?: AnnotationCstNode[];
  typeArguments?: TypeArgumentsCstNode[];
  fqnOrRefTypePartCommon: FqnOrRefTypePartCommonCstNode[];
};

export interface FqnOrRefTypePartCommonCstNode extends CstNode {
  name: "fqnOrRefTypePartCommon";
  children: FqnOrRefTypePartCommonCtx;
}

export type FqnOrRefTypePartCommonCtx = {
  Identifier?: IToken[];
  Super?: IToken[];
  typeArguments?: TypeArgumentsCstNode[];
};

export interface FqnOrRefTypePartFirstCstNode extends CstNode {
  name: "fqnOrRefTypePartFirst";
  children: FqnOrRefTypePartFirstCtx;
}

export type FqnOrRefTypePartFirstCtx = {
  annotation?: AnnotationCstNode[];
  fqnOrRefTypePartCommon: FqnOrRefTypePartCommonCstNode[];
};

export interface ParenthesisExpressionCstNode extends CstNode {
  name: "parenthesisExpression";
  children: ParenthesisExpressionCtx;
}

export type ParenthesisExpressionCtx = {
  LBrace: IToken[];
  expression: ExpressionCstNode[];
  RBrace: IToken[];
};

export interface CastExpressionCstNode extends CstNode {
  name: "castExpression";
  children: CastExpressionCtx;
}

export type CastExpressionCtx = {
  primitiveCastExpression?: PrimitiveCastExpressionCstNode[];
  referenceTypeCastExpression?: ReferenceTypeCastExpressionCstNode[];
};

export interface PrimitiveCastExpressionCstNode extends CstNode {
  name: "primitiveCastExpression";
  children: PrimitiveCastExpressionCtx;
}

export type PrimitiveCastExpressionCtx = {
  LBrace: IToken[];
  primitiveType: PrimitiveTypeCstNode[];
  RBrace: IToken[];
  unaryExpression: UnaryExpressionCstNode[];
};

export interface ReferenceTypeCastExpressionCstNode extends CstNode {
  name: "referenceTypeCastExpression";
  children: ReferenceTypeCastExpressionCtx;
}

export type ReferenceTypeCastExpressionCtx = {
  LBrace: IToken[];
  referenceType: ReferenceTypeCstNode[];
  additionalBound?: AdditionalBoundCstNode[];
  RBrace: IToken[];
  lambdaExpression?: LambdaExpressionCstNode[];
  unaryExpressionNotPlusMinus?: UnaryExpressionNotPlusMinusCstNode[];
};

export interface NewExpressionCstNode extends CstNode {
  name: "newExpression";
  children: NewExpressionCtx;
}

export type NewExpressionCtx = {
  arrayCreationExpression?: ArrayCreationExpressionCstNode[];
  unqualifiedClassInstanceCreationExpression?: UnqualifiedClassInstanceCreationExpressionCstNode[];
};

export interface UnqualifiedClassInstanceCreationExpressionCstNode
  extends CstNode {
  name: "unqualifiedClassInstanceCreationExpression";
  children: UnqualifiedClassInstanceCreationExpressionCtx;
}

export type UnqualifiedClassInstanceCreationExpressionCtx = {
  New: IToken[];
  typeArguments?: TypeArgumentsCstNode[];
  classOrInterfaceTypeToInstantiate: ClassOrInterfaceTypeToInstantiateCstNode[];
  LBrace: IToken[];
  argumentList?: ArgumentListCstNode[];
  RBrace: IToken[];
  classBody?: ClassBodyCstNode[];
};

export interface ClassOrInterfaceTypeToInstantiateCstNode extends CstNode {
  name: "classOrInterfaceTypeToInstantiate";
  children: ClassOrInterfaceTypeToInstantiateCtx;
}

export type ClassOrInterfaceTypeToInstantiateCtx = {
  annotation?: AnnotationCstNode[];
  Identifier: IToken[];
  Dot?: IToken[];
  typeArgumentsOrDiamond?: TypeArgumentsOrDiamondCstNode[];
};

export interface TypeArgumentsOrDiamondCstNode extends CstNode {
  name: "typeArgumentsOrDiamond";
  children: TypeArgumentsOrDiamondCtx;
}

export type TypeArgumentsOrDiamondCtx = {
  diamond?: DiamondCstNode[];
  typeArguments?: TypeArgumentsCstNode[];
};

export interface DiamondCstNode extends CstNode {
  name: "diamond";
  children: DiamondCtx;
}

export type DiamondCtx = {
  Less: IToken[];
  Greater: IToken[];
};

export interface MethodInvocationSuffixCstNode extends CstNode {
  name: "methodInvocationSuffix";
  children: MethodInvocationSuffixCtx;
}

export type MethodInvocationSuffixCtx = {
  LBrace: IToken[];
  argumentList?: ArgumentListCstNode[];
  RBrace: IToken[];
};

export interface ArgumentListCstNode extends CstNode {
  name: "argumentList";
  children: ArgumentListCtx;
}

export type ArgumentListCtx = {
  expression: ExpressionCstNode[];
  Comma?: IToken[];
};

export interface ArrayCreationExpressionCstNode extends CstNode {
  name: "arrayCreationExpression";
  children: ArrayCreationExpressionCtx;
}

export type ArrayCreationExpressionCtx = {
  New: IToken[];
  primitiveType?: PrimitiveTypeCstNode[];
  classOrInterfaceType?: ClassOrInterfaceTypeCstNode[];
  arrayCreationDefaultInitSuffix?: ArrayCreationDefaultInitSuffixCstNode[];
  arrayCreationExplicitInitSuffix?: ArrayCreationExplicitInitSuffixCstNode[];
};

export interface ArrayCreationDefaultInitSuffixCstNode extends CstNode {
  name: "arrayCreationDefaultInitSuffix";
  children: ArrayCreationDefaultInitSuffixCtx;
}

export type ArrayCreationDefaultInitSuffixCtx = {
  dimExprs: DimExprsCstNode[];
  dims?: DimsCstNode[];
};

export interface ArrayCreationExplicitInitSuffixCstNode extends CstNode {
  name: "arrayCreationExplicitInitSuffix";
  children: ArrayCreationExplicitInitSuffixCtx;
}

export type ArrayCreationExplicitInitSuffixCtx = {
  dims: DimsCstNode[];
  arrayInitializer: ArrayInitializerCstNode[];
};

export interface DimExprsCstNode extends CstNode {
  name: "dimExprs";
  children: DimExprsCtx;
}

export type DimExprsCtx = {
  dimExpr: DimExprCstNode[];
};

export interface DimExprCstNode extends CstNode {
  name: "dimExpr";
  children: DimExprCtx;
}

export type DimExprCtx = {
  annotation?: AnnotationCstNode[];
  LSquare: IToken[];
  expression: ExpressionCstNode[];
  RSquare: IToken[];
};

export interface ClassLiteralSuffixCstNode extends CstNode {
  name: "classLiteralSuffix";
  children: ClassLiteralSuffixCtx;
}

export type ClassLiteralSuffixCtx = {
  LSquare?: IToken[];
  RSquare?: IToken[];
  Dot: IToken[];
  Class: IToken[];
};

export interface ArrayAccessSuffixCstNode extends CstNode {
  name: "arrayAccessSuffix";
  children: ArrayAccessSuffixCtx;
}

export type ArrayAccessSuffixCtx = {
  LSquare: IToken[];
  expression: ExpressionCstNode[];
  RSquare: IToken[];
};

export interface MethodReferenceSuffixCstNode extends CstNode {
  name: "methodReferenceSuffix";
  children: MethodReferenceSuffixCtx;
}

export type MethodReferenceSuffixCtx = {
  ColonColon: IToken[];
  typeArguments?: TypeArgumentsCstNode[];
  Identifier?: IToken[];
  New?: IToken[];
};

export interface TemplateArgumentCstNode extends CstNode {
  name: "templateArgument";
  children: TemplateArgumentCtx;
}

export type TemplateArgumentCtx = {
  template?: TemplateCstNode[];
  StringLiteral?: IToken[];
  TextBlock?: IToken[];
};

export interface TemplateCstNode extends CstNode {
  name: "template";
  children: TemplateCtx;
}

export type TemplateCtx = {
  stringTemplate?: StringTemplateCstNode[];
  textBlockTemplate?: TextBlockTemplateCstNode[];
};

export interface StringTemplateCstNode extends CstNode {
  name: "stringTemplate";
  children: StringTemplateCtx;
}

export type StringTemplateCtx = {
  StringTemplateBegin: IToken[];
  embeddedExpression: EmbeddedExpressionCstNode[];
  StringTemplateMid?: IToken[];
  StringTemplateEnd: IToken[];
};

export interface TextBlockTemplateCstNode extends CstNode {
  name: "textBlockTemplate";
  children: TextBlockTemplateCtx;
}

export type TextBlockTemplateCtx = {
  TextBlockTemplateBegin: IToken[];
  embeddedExpression: EmbeddedExpressionCstNode[];
  TextBlockTemplateMid?: IToken[];
  TextBlockTemplateEnd: IToken[];
};

export interface EmbeddedExpressionCstNode extends CstNode {
  name: "embeddedExpression";
  children: EmbeddedExpressionCtx;
}

export type EmbeddedExpressionCtx = {
  expression?: ExpressionCstNode[];
};

export interface PatternCstNode extends CstNode {
  name: "pattern";
  children: PatternCtx;
}

export type PatternCtx = {
  typePattern?: TypePatternCstNode[];
  recordPattern?: RecordPatternCstNode[];
};

export interface TypePatternCstNode extends CstNode {
  name: "typePattern";
  children: TypePatternCtx;
}

export type TypePatternCtx = {
  localVariableDeclaration: LocalVariableDeclarationCstNode[];
};

export interface RecordPatternCstNode extends CstNode {
  name: "recordPattern";
  children: RecordPatternCtx;
}

export type RecordPatternCtx = {
  referenceType: ReferenceTypeCstNode[];
  LBrace: IToken[];
  componentPatternList?: ComponentPatternListCstNode[];
  RBrace: IToken[];
};

export interface ComponentPatternListCstNode extends CstNode {
  name: "componentPatternList";
  children: ComponentPatternListCtx;
}

export type ComponentPatternListCtx = {
  componentPattern: ComponentPatternCstNode[];
  Comma?: IToken[];
};

export interface ComponentPatternCstNode extends CstNode {
  name: "componentPattern";
  children: ComponentPatternCtx;
}

export type ComponentPatternCtx = {
  pattern?: PatternCstNode[];
  unnamedPattern?: UnnamedPatternCstNode[];
};

export interface UnnamedPatternCstNode extends CstNode {
  name: "unnamedPattern";
  children: UnnamedPatternCtx;
}

export type UnnamedPatternCtx = {
  Underscore: IToken[];
};

export interface GuardCstNode extends CstNode {
  name: "guard";
  children: GuardCtx;
}

export type GuardCtx = {
  When: IToken[];
  expression: ExpressionCstNode[];
};

export interface IsRefTypeInMethodRefCstNode extends CstNode {
  name: "isRefTypeInMethodRef";
  children: IsRefTypeInMethodRefCtx;
}

export type IsRefTypeInMethodRefCtx = {
  typeArguments: TypeArgumentsCstNode[];
  dims?: DimsCstNode[];
  Dot?: IToken[];
  classOrInterfaceType?: ClassOrInterfaceTypeCstNode[];
};
