package com.strumenta.kolasu.transformation

import com.strumenta.kolasu.ast.Range
import com.strumenta.kolasu.model.Node
import com.strumenta.kolasu.model.NodeLike
import com.strumenta.kolasu.model.NodeOrigin
import com.strumenta.kolasu.model.hasValidParents
import com.strumenta.kolasu.model.withOrigin
import com.strumenta.kolasu.testing.assertASTsAreEqual
import com.strumenta.kolasu.validation.Issue
import com.strumenta.kolasu.validation.IssueSeverity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

data class CU(
    val specifiedRange: Range? = null,
    var statements: List<NodeLike> = listOf(),
) : Node(specifiedRange)

data class DisplayIntStatement(
    val specifiedRange: Range? = null,
    val value: Int,
) : Node(specifiedRange)

data class SetStatement(
    val specifiedRange: Range? = null,
    var variable: String = "",
    val value: Int = 0,
) : Node(specifiedRange)

enum class Operator {
    PLUS,
    MULT,
}

sealed class Expression : Node()

data class IntLiteral(
    val value: Int,
) : Expression()

data class GenericBinaryExpression(
    val operator: Operator,
    val left: Expression,
    val right: Expression,
) : Node()

data class Mult(
    val left: Expression,
    val right: Expression,
) : Node()

data class Sum(
    val left: Expression,
    val right: Expression,
) : Node()

sealed class ALangExpression : Node()

data class ALangIntLiteral(
    val value: Int,
) : ALangExpression()

data class ALangSum(
    val left: ALangExpression,
    val right: ALangExpression,
) : ALangExpression()

data class ALangMult(
    val left: ALangExpression,
    val right: ALangExpression,
) : ALangExpression()

sealed class BLangExpression : Node()

data class BLangIntLiteral(
    val value: Int,
) : BLangExpression()

data class BLangSum(
    val left: BLangExpression,
    val right: BLangExpression,
) : BLangExpression()

data class BLangMult(
    val left: BLangExpression,
    val right: BLangExpression,
) : BLangExpression()

enum class Type {
    INT,
    STR,
}

sealed class TypedExpression(
    open var type: Type? = null,
) : Node()

data class TypedLiteral(
    var value: String,
    override var type: Type?,
) : TypedExpression(type)

data class TypedSum(
    var left: TypedExpression,
    var right: TypedExpression,
    override var type: Type? = null,
) : TypedExpression(type)

data class TypedConcat(
    var left: TypedExpression,
    var right: TypedExpression,
    override var type: Type? = null,
) : TypedExpression(type)

class ASTTransformerTest {
    @Test
    fun testIdentitiyTransformer() {
        val transformer = ASTTransformer()
        transformer
            .registerNodeTransformer(CU::class, CU::class)
            .withChild(CU::statements, CU::statements)
        transformer.registerIdentityTransformation(DisplayIntStatement::class)
        transformer.registerIdentityTransformation(SetStatement::class)

        val cu =
            CU(
                statements =
                    listOf(
                        SetStatement(variable = "foo", value = 123),
                        DisplayIntStatement(value = 456),
                    ),
            )
        val transformedCU = transformer.transform(cu)!!
        assertASTsAreEqual(cu, transformedCU, considerRange = true)
        assertTrue { transformedCU.hasValidParents() }
        assertEquals(NodeOrigin(cu), transformedCU.origin)
    }

    /**
     * Example of transformation to perform a refactoring within the same language.
     */
    @Test
    fun translateBinaryExpression() {
        val myTransformer =
            ASTTransformer(allowGenericNode = false).apply {
                registerNodeTransformer(GenericBinaryExpression::class) { source: GenericBinaryExpression ->
                    when (source.operator) {
                        Operator.MULT ->
                            Mult(
                                transform(source.left) as Expression,
                                transform(source.right) as Expression,
                            )

                        Operator.PLUS ->
                            Sum(
                                transform(source.left) as Expression,
                                transform(source.right) as Expression,
                            )
                    }
                }
                registerIdentityTransformation(IntLiteral::class)
            }
        assertASTsAreEqual(
            Mult(IntLiteral(7), IntLiteral(8)),
            myTransformer.transform(GenericBinaryExpression(Operator.MULT, IntLiteral(7), IntLiteral(8)))!!,
        )
        assertASTsAreEqual(
            Sum(IntLiteral(7), IntLiteral(8)),
            myTransformer.transform(GenericBinaryExpression(Operator.PLUS, IntLiteral(7), IntLiteral(8)))!!,
        )
    }

    /**
     * Example of transformation to perform a translation to another language.
     */
    @Test
    fun translateAcrossLanguages() {
        val myTransformer =
            ASTTransformer(allowGenericNode = false).apply {
                registerNodeTransformer(
                    ALangIntLiteral::class,
                ) { source: ALangIntLiteral -> BLangIntLiteral(source.value) }
                registerNodeTransformer(ALangSum::class) { source: ALangSum ->
                    BLangSum(transform(source.left) as BLangExpression, transform(source.right) as BLangExpression)
                }
                registerNodeTransformer(ALangMult::class) { source: ALangMult ->
                    BLangMult(transform(source.left) as BLangExpression, transform(source.right) as BLangExpression)
                }
            }
        assertASTsAreEqual(
            BLangMult(
                BLangSum(
                    BLangIntLiteral(1),
                    BLangMult(BLangIntLiteral(2), BLangIntLiteral(3)),
                ),
                BLangIntLiteral(4),
            ),
            myTransformer.transform(
                ALangMult(
                    ALangSum(
                        ALangIntLiteral(1),
                        ALangMult(ALangIntLiteral(2), ALangIntLiteral(3)),
                    ),
                    ALangIntLiteral(4),
                ),
            )!!,
        )
    }

    /**
     * Example of transformation to perform a simple type calculation.
     */
    @Test
    fun computeTypes() {
        val myTransformer =
            ASTTransformer(allowGenericNode = false).apply {
                registerIdentityTransformation(TypedSum::class).withFinalizer {
                    if (it.left.type == Type.INT && it.right.type == Type.INT) {
                        it.type = Type.INT
                    } else {
                        addIssue(
                            "Illegal types for sum operation. Only integer values are allowed. " +
                                "Found: (${it.left.type?.name ?: "null"}, ${it.right.type?.name ?: "null"})",
                            IssueSeverity.ERROR,
                            it.range,
                        )
                    }
                }
                registerIdentityTransformation(TypedConcat::class).withFinalizer {
                    if (it.left.type == Type.STR && it.right.type == Type.STR) {
                        it.type = Type.STR
                    } else {
                        addIssue(
                            "Illegal types for concat operation. Only string values are allowed. " +
                                "Found: (${it.left.type?.name ?: "null"}, ${it.right.type?.name ?: "null"})",
                            IssueSeverity.ERROR,
                            it.range,
                        )
                    }
                }
                registerIdentityTransformation(TypedLiteral::class)
            }
        // sum - legal
        assertASTsAreEqual(
            TypedSum(
                TypedLiteral("1", Type.INT),
                TypedLiteral("1", Type.INT),
                Type.INT,
            ),
            myTransformer.transform(
                TypedSum(
                    TypedLiteral("1", Type.INT),
                    TypedLiteral("1", Type.INT),
                ),
            )!!,
        )
        assertEquals(0, myTransformer.issues.size)
        // concat - legal
        assertASTsAreEqual(
            TypedConcat(
                TypedLiteral("test", Type.STR),
                TypedLiteral("test", Type.STR),
                Type.STR,
            ),
            myTransformer.transform(
                TypedConcat(
                    TypedLiteral("test", Type.STR),
                    TypedLiteral("test", Type.STR),
                ),
            )!!,
        )
        assertEquals(0, myTransformer.issues.size)
        // sum - error
        assertASTsAreEqual(
            TypedSum(
                TypedLiteral("1", Type.INT),
                TypedLiteral("test", Type.STR),
                null,
            ),
            myTransformer.transform(
                TypedSum(
                    TypedLiteral("1", Type.INT),
                    TypedLiteral("test", Type.STR),
                ),
            )!!,
        )
        assertEquals(1, myTransformer.issues.size)
        assertEquals(
            Issue.semantic(
                "Illegal types for sum operation. Only integer values are allowed. Found: (INT, STR)",
                IssueSeverity.ERROR,
            ),
            myTransformer.issues[0],
        )
        // concat - error
        assertASTsAreEqual(
            TypedConcat(
                TypedLiteral("1", Type.INT),
                TypedLiteral("test", Type.STR),
                null,
            ),
            myTransformer.transform(
                TypedConcat(
                    TypedLiteral("1", Type.INT),
                    TypedLiteral("test", Type.STR),
                ),
            )!!,
        )
        assertEquals(2, myTransformer.issues.size)
        assertEquals(
            Issue.semantic(
                "Illegal types for concat operation. Only string values are allowed. Found: (INT, STR)",
                IssueSeverity.ERROR,
            ),
            myTransformer.issues[1],
        )
    }

    @Test
    fun testDroppingNodes() {
        val transformer = ASTTransformer()
        transformer
            .registerNodeTransformer(CU::class, CU::class)
            .withChild(CU::statements, CU::statements)
        transformer.registerNodeTransformer(DisplayIntStatement::class) { _ -> null }
        transformer.registerIdentityTransformation(SetStatement::class)

        val cu =
            CU(
                statements =
                    listOf(
                        DisplayIntStatement(value = 456),
                        SetStatement(variable = "foo", value = 123),
                    ),
            )
        val transformedCU = transformer.transform(cu)!! as CU
        assertTrue { transformedCU.hasValidParents() }
        assertEquals(transformedCU.origin, NodeOrigin(cu))
        assertEquals(1, transformedCU.statements.size)
        assertASTsAreEqual(cu.statements[1], transformedCU.statements[0])
    }

    @Test
    fun testNestedOrigin() {
        val transformer = ASTTransformer()
        transformer
            .registerNodeTransformer(CU::class, CU::class)
            .withChild(CU::statements, CU::statements)
        transformer.registerNodeTransformer(DisplayIntStatement::class) { s ->
            s.withOrigin(GenericNode())
        }

        val cu =
            CU(
                statements =
                    listOf(
                        DisplayIntStatement(value = 456),
                    ),
            )
        val transformedCU = transformer.transform(cu)!! as CU
        assertTrue { transformedCU.hasValidParents() }
        assertEquals(transformedCU.origin, NodeOrigin(cu))
        assertIs<GenericNode>((transformedCU.statements[0].origin as NodeOrigin).node)
    }

    @Test
    fun testTransformingOneNodeToMany() {
        val transformer = ASTTransformer()
        transformer
            .registerNodeTransformer(BarRoot::class, BazRoot::class)
            .withChild(BazRoot::stmts, BarRoot::stmts)
        transformer.registerMultipleNodeTransformer(BarStmt::class) { s ->
            listOf(BazStmt("${s.desc}-1"), BazStmt("${s.desc}-2"))
        }

        val original =
            BarRoot(
                stmts =
                    mutableListOf(
                        BarStmt("a"),
                        BarStmt("b"),
                    ),
            )
        val transformed = transformer.transform(original) as NodeLike
        assertTrue { transformed.hasValidParents() }
        assertEquals(transformed.origin, NodeOrigin(original))
        assertASTsAreEqual(
            BazRoot(
                mutableListOf(
                    BazStmt("a-1"),
                    BazStmt("a-2"),
                    BazStmt("b-1"),
                    BazStmt("b-2"),
                ),
            ),
            transformed,
        )
    }
}

data class BazRoot(
    var stmts: MutableList<BazStmt> = mutableListOf(),
) : Node()

data class BazStmt(
    val desc: String,
) : Node()

data class BarRoot(
    var stmts: MutableList<BarStmt> = mutableListOf(),
) : Node()

data class BarStmt(
    val desc: String,
) : Node()
