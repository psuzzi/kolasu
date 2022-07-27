package com.strumenta.kolasu.model

import com.strumenta.kolasu.parsing.ParsingResult
import java.lang.reflect.ParameterizedType
import kotlin.reflect.KProperty1
import kotlin.reflect.KVisibility.INTERNAL
import kotlin.reflect.KVisibility.PRIVATE
import kotlin.reflect.KVisibility.PROTECTED
import kotlin.reflect.KVisibility.PUBLIC
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaType

private const val indentBlock = "  "

fun<T: Node> T.relevantMemberProperties(withPosition: Boolean = false, withNodeType: Boolean = false):
    List<KProperty1<T, *>> {
    val list = this::class.nodeProperties.map { it as KProperty1<T, *> }.toMutableList()
    if (withPosition) {
        list.add(Node::position as KProperty1<T, *>)
    }
    if (withNodeType) {
        list.add(Node::nodeType as KProperty1<T, *>)
    }
    return list.toList()
}

data class DebugPrintConfiguration constructor(
    var skipEmptyCollections: Boolean = false,
    var skipNull: Boolean = false,
    var forceShowPosition: Boolean = false,
    val hide: MutableList<String> = mutableListOf()
)

private fun KProperty1<Node, *>.hasRelevantVisibility(configuration: DebugPrintConfiguration): Boolean {
    return when (requireNotNull(this.visibility)) {
        PRIVATE -> false
        PROTECTED -> false
        INTERNAL -> false
        PUBLIC -> true
    }
}

private fun Node.showSingleAttribute(indent: String, sb: StringBuilder, propertyName: String, value: Any?) {
    sb.append("$indent$indentBlock$propertyName = ${value}\n")
}

fun Any?.debugPrint(indent: String = "", configuration: DebugPrintConfiguration = DebugPrintConfiguration()): String {
    val sb = StringBuilder()
    sb.append("$indent${this}\n")
    return sb.toString()
}

fun <N : Node> ParsingResult<N>.debugPrint(
    indent: String = "",
    configuration: DebugPrintConfiguration = DebugPrintConfiguration()
): String {
    val sb = StringBuilder()
    sb.append("${indent}Result {\n")
    sb.append("${indent}${indentBlock}issues= [\n")
    sb.append("${indent}$indentBlock]\n")
    if (this.root == null) {
        sb.append("${indent}${indentBlock}root = null\n")
    } else {
        sb.append("${indent}${indentBlock}root = [\n")
        sb.append(this.root!!.debugPrint(indent + indentBlock + indentBlock, configuration = configuration))
        sb.append("${indent}$indentBlock]\n")
    }
    sb.append("$indent}\n")
    return sb.toString()
}

// some fancy reflection tests make sure the cast always succeeds
@Suppress("UNCHECKED_CAST")
@JvmOverloads
fun Node.debugPrint(indent: String = "", configuration: DebugPrintConfiguration = DebugPrintConfiguration()): String {
    val sb = StringBuilder()
    if (this.relevantMemberProperties(withPosition = configuration.forceShowPosition).isEmpty()) {
        sb.append("$indent${this.javaClass.simpleName}\n")
    } else {
        sb.append("$indent${this.javaClass.simpleName} {\n")
        this.relevantMemberProperties(withPosition = configuration.forceShowPosition).forEach { property ->
            if (configuration.hide.contains(property.name)) {
                // skipping
            } else {
                val mt = property.returnType.javaType
                if (mt is ParameterizedType && mt.rawType == List::class.java) {
                    property.isAccessible = true
                    if (property.get(this) == null && !configuration.skipNull) {
                        sb.append("$indent$indentBlock${property.name} = null")
                    } else {
                        val value = property.get(this) as List<*>
                        if (value.isEmpty()) {
                            if (configuration.skipEmptyCollections) {
                                // nothing to do
                            } else {
                                sb.append("$indent$indentBlock${property.name} = []\n")
                            }
                        } else {
                            val paramType = mt.actualTypeArguments[0]
                            if (paramType is Class<*> && paramType.kotlin.isANode()) {
                                sb.append("$indent$indentBlock${property.name} = [\n")
                                (value as List<Node>).forEach {
                                    sb.append(
                                        it.debugPrint(
                                            indent + indentBlock + indentBlock, configuration
                                        )
                                    )
                                }
                                sb.append("$indent$indentBlock]\n")
                            } else {
                                sb.append("$indent$indentBlock${property.name} = [\n")
                                value.forEach {
                                    sb.append(
                                        it?.debugPrint(
                                            indent + indentBlock + indentBlock, configuration
                                        )
                                    )
                                }
                                sb.append("$indent$indentBlock]\n")
                            }
                        }
                    }
                } else {
                    property.isAccessible = true
                    val value = property.get(this)
                    if (value == null && configuration.skipNull) {
                        // nothing to do
                    } else {
                        if (value is Node) {
                            sb.append("$indent$indentBlock${property.name} = [\n")
                            sb.append(
                                value.debugPrint(
                                    indent + indentBlock + indentBlock, configuration
                                )
                            )
                            sb.append("$indent$indentBlock]\n")
                        } else {
                            this.showSingleAttribute(indent, sb, property.name, value)
                        }
                    }
                }
            }
        }
        sb.append("$indent} // ${this.javaClass.simpleName}\n")
    }
    return sb.toString()
}
