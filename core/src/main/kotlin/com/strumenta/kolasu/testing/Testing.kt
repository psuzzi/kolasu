package com.strumenta.kolasu.testing

import com.strumenta.kolasu.model.FeatureType
import com.strumenta.kolasu.model.KReferenceByName
import com.strumenta.kolasu.model.NodeLike
import com.strumenta.kolasu.model.PossiblyNamed
import com.strumenta.kolasu.model.ReferenceByName
import com.strumenta.kolasu.model.kReferenceByNameProperties
import com.strumenta.kolasu.parsing.ParsingResult
import com.strumenta.kolasu.traversing.walkChildren
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class IgnoreChildren<N : NodeLike> : MutableList<N> {
    override val size: Int
        get() = TODO("Not yet implemented")

    override fun clear() {
        TODO("Not yet implemented")
    }

    override fun addAll(elements: Collection<N>): Boolean {
        TODO("Not yet implemented")
    }

    override fun addAll(
        index: Int,
        elements: Collection<N>,
    ): Boolean {
        TODO("Not yet implemented")
    }

    override fun add(
        index: Int,
        element: N,
    ) {
        TODO("Not yet implemented")
    }

    override fun add(element: N): Boolean {
        TODO("Not yet implemented")
    }

    override fun contains(element: N): Boolean {
        TODO("Not yet implemented")
    }

    override fun containsAll(elements: Collection<N>): Boolean {
        TODO("Not yet implemented")
    }

    override fun get(index: Int): N {
        TODO("Not yet implemented")
    }

    override fun indexOf(element: N): Int {
        TODO("Not yet implemented")
    }

    override fun isEmpty(): Boolean {
        TODO("Not yet implemented")
    }

    override fun iterator(): MutableIterator<N> {
        TODO("Not yet implemented")
    }

    override fun lastIndexOf(element: N): Int {
        TODO("Not yet implemented")
    }

    override fun listIterator(): MutableListIterator<N> {
        TODO("Not yet implemented")
    }

    override fun listIterator(index: Int): MutableListIterator<N> {
        TODO("Not yet implemented")
    }

    override fun removeAt(index: Int): N {
        TODO("Not yet implemented")
    }

    override fun set(
        index: Int,
        element: N,
    ): N {
        TODO("Not yet implemented")
    }

    override fun retainAll(elements: Collection<N>): Boolean {
        TODO("Not yet implemented")
    }

    override fun removeAll(elements: Collection<N>): Boolean {
        TODO("Not yet implemented")
    }

    override fun remove(element: N): Boolean {
        TODO("Not yet implemented")
    }

    override fun subList(
        fromIndex: Int,
        toIndex: Int,
    ): MutableList<N> {
        TODO("Not yet implemented")
    }
}

class ASTDifferenceException(
    val context: String,
    val expected: Any,
    val actual: Any,
) : Exception("$context: expecting $expected, actual $actual")

fun <T : NodeLike> assertParsingResultsAreEqual(
    expected: ParsingResult<T>,
    actual: ParsingResult<T>,
) {
    assertEquals(expected.issues, actual.issues)
    assertEquals(expected.root != null, actual.root != null)
    if (expected.root != null) {
        assertASTsAreEqual(expected.root!!, actual.root!!)
    }
}

fun <N : NodeLike> assertASTsAreEqual(
    expected: NodeLike,
    actual: ParsingResult<N>,
    context: String = "<root>",
    considerRange: Boolean = false,
) {
    assertEquals(0, actual.issues.size, actual.issues.toString())
    assertASTsAreEqual(
        expected = expected,
        actual = actual.root!!,
        context = context,
        considerRange = considerRange,
    )
}

fun assertASTsAreEqual(
    expected: NodeLike,
    actual: NodeLike,
    context: String = "<root>",
    considerRange: Boolean = false,
    useLightweightAttributeEquality: Boolean = false,
) {
    if (expected.nodeType == actual.nodeType) {
        if (considerRange) {
            assertEquals(expected.range, actual.range, "$context.range")
        }
        expected.originalFeatures.forEach { expectedProperty ->
            try {
                val actualProperty =
                    actual.originalFeatures.find { it.name == expectedProperty.name }
                        ?: fail("No property ${expectedProperty.name} found at $context")
                val actualPropValue = actualProperty.value
                val expectedPropValue = expectedProperty.value
                if (expectedProperty.provideNodes) {
                    if (expectedProperty.isMultiple) {
                        if (expectedPropValue is IgnoreChildren<*>) {
                            // Nothing to do
                        } else {
                            val actualPropValueCollection = actualPropValue?.let { it as Collection<NodeLike> }
                            val expectedPropValueCollection = expectedPropValue?.let { it as Collection<NodeLike> }
                            assertEquals(
                                actualPropValueCollection == null,
                                expectedPropValueCollection == null,
                                "$context.${expectedProperty.name} nullness",
                            )
                            if (actualPropValueCollection != null && expectedPropValueCollection != null) {
                                assertEquals(
                                    expectedPropValueCollection?.size,
                                    actualPropValueCollection?.size,
                                    "$context.${expectedProperty.name} length",
                                )
                                val expectedIt = expectedPropValueCollection.iterator()
                                val actualIt = actualPropValueCollection.iterator()
                                for (i in expectedPropValueCollection.indices) {
                                    assertASTsAreEqual(
                                        expectedIt.next(),
                                        actualIt.next(),
                                        "$context[$i]",
                                        considerRange = considerRange,
                                        useLightweightAttributeEquality = useLightweightAttributeEquality,
                                    )
                                }
                            }
                        }
                    } else {
                        if (expectedPropValue == null && actualPropValue != null) {
                            assertEquals<Any?>(
                                expectedPropValue,
                                actualPropValue,
                                "$context.${expectedProperty.name}",
                            )
                        } else if (expectedPropValue != null && actualPropValue == null) {
                            assertEquals<Any?>(
                                expectedPropValue,
                                actualPropValue,
                                "$context.${expectedProperty.name}",
                            )
                        } else if (expectedPropValue == null && actualPropValue == null) {
                            // that is ok
                        } else {
                            assertASTsAreEqual(
                                expectedPropValue as NodeLike,
                                actualPropValue as NodeLike,
                                context = "$context.${expectedProperty.name}",
                                considerRange = considerRange,
                                useLightweightAttributeEquality = useLightweightAttributeEquality,
                            )
                        }
                    }
                } else if (expectedProperty.featureType == FeatureType.REFERENCE) {
                    if (expectedPropValue is ReferenceByName<*> && actualPropValue is ReferenceByName<*>) {
                        assertEquals(
                            expectedPropValue.name,
                            actualPropValue.name,
                            "$context, comparing reference name of ${expectedProperty.name} of ${expected.nodeType}",
                        )
                        assertEquals(
                            expectedPropValue.referred?.toString(),
                            actualPropValue.referred?.toString(),
                            "$context, comparing reference pointer ${expectedProperty.name} of ${expected.nodeType}",
                        )
                    } else {
                        TODO()
                    }
                } else {
                    if (useLightweightAttributeEquality) {
                        assertEquals(
                            expectedPropValue?.toString(),
                            actualPropValue?.toString(),
                            "$context, comparing property ${expectedProperty.name} of ${expected.nodeType}",
                        )
                    } else {
                        assertEquals(
                            expectedPropValue,
                            actualPropValue,
                            "$context, comparing property ${expectedProperty.name} of ${expected.nodeType}",
                        )
                    }
                }
            } catch (e: Exception) {
                throw RuntimeException("Issue while processing property $expectedProperty of $expected", e)
            }
        }
    } else {
        fail(
            "$context: expected node of type ${expected.nodeType}, " +
                "but found ${actual.nodeType}",
        )
    }
}

fun NodeLike.assertReferencesResolved(forProperty: KReferenceByName<out NodeLike>) {
    this
        .kReferenceByNameProperties()
        .filter { it == forProperty }
        .mapNotNull { it.get(this) }
        .forEach { assertTrue { (it as ReferenceByName<*>).isResolved } }
    this.walkChildren().forEach { it.assertReferencesResolved(forProperty = forProperty) }
}

fun NodeLike.assertReferencesResolved(withReturnType: KClass<out PossiblyNamed> = PossiblyNamed::class) {
    this
        .kReferenceByNameProperties(targetClass = withReturnType)
        .mapNotNull { it.get(this) }
        .forEach { assertTrue { (it as ReferenceByName<*>).isResolved } }
    this.walkChildren().forEach { it.assertReferencesResolved(withReturnType = withReturnType) }
}

fun NodeLike.assertReferencesNotResolved(forProperty: KReferenceByName<out NodeLike>) {
    this
        .kReferenceByNameProperties()
        .filter { it == forProperty }
        .mapNotNull { it.get(this) }
        .forEach { assertFalse { (it as ReferenceByName<*>).isResolved } }
    this.walkChildren().forEach { it.assertReferencesNotResolved(forProperty = forProperty) }
}

fun NodeLike.assertReferencesNotResolved(withReturnType: KClass<out PossiblyNamed> = PossiblyNamed::class) {
    this
        .kReferenceByNameProperties(targetClass = withReturnType)
        .mapNotNull { it.get(this) }
        .forEach { assertFalse { (it as ReferenceByName<*>).isResolved } }
    this.walkChildren().forEach { it.assertReferencesNotResolved(withReturnType = withReturnType) }
}
