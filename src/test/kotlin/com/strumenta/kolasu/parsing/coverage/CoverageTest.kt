package com.strumenta.kolasu.parsing.coverage

import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.junit.Test
import com.strumenta.simplelang.SimpleLangLexer
import com.strumenta.simplelang.SimpleLangParser
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoverageTest {

    @Test
    fun initiallyEmpty() {
        val coverage = CoverageListener()
        assertTrue { coverage.uncoveredPaths().isEmpty() }
        assertTrue { coverage.paths.isEmpty() }
    }

    @Test
    fun coverageOfSimpleRule() {
        val code = "int"
        val lexer = SimpleLangLexer(CharStreams.fromString(code))
        val parser = SimpleLangParser(CommonTokenStream(lexer))
        val coverage = CoverageListener()
        coverage.listenTo(parser)
        parser.type()
        assertTrue { coverage.uncoveredPathStrings().containsAll(listOf(
            "type > DEC", "type > STRING", "type > BOOLEAN"))
        }
        assertCoverage(coverage, 3, 4)
    }

    private fun assertCoverage(coverage: CoverageListener, expectedUncovered: Int, expectedPaths: Int) {
        assertEquals(expectedUncovered, coverage.uncoveredPaths().size)
        assertEquals(expectedPaths, coverage.paths.size)
        assertTrue { abs((expectedPaths.toDouble() - expectedUncovered.toDouble()) / expectedPaths.toDouble() - coverage.percentage()) < .00001 }
    }

    @Test
    fun coverageOfPlusBlock() {
        val code = "set foo = 123"
        val lexer = SimpleLangLexer(CharStreams.fromString(code))
        val parser = SimpleLangParser(CommonTokenStream(lexer))
        val coverage = CoverageListener()
        coverage.listenTo(parser)
        parser.compilationUnit()
        assertTrue { coverage.uncoveredPathStrings().containsAll(listOf(
            "compilationUnit > statement > DISPLAY",
            "compilationUnit > statement > INPUT",
            "compilationUnit > statement > SET > ID > EQUAL > expression > DEC_LIT",
            "compilationUnit > statement > SET > ID > EQUAL > expression > STRING_LIT",
            "compilationUnit > statement > SET > ID > EQUAL > expression > BOOLEAN_LIT",
            "compilationUnit > statement > SET > ID > EQUAL > expression > statement",
            "compilationUnit > statement > statement"))
        }
        assertCoverage(coverage, 7, 12)
    }

}