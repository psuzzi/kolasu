package com.strumenta.kolasu.model

import com.strumenta.kolasu.model.observable.Observer
import java.io.Serializable
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.KVisibility
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties

interface Origin {
    val range: Range?
    val sourceText: String?
    val source: Source?
        get() = range?.source
}

class SimpleOrigin(override val range: Range?, override val sourceText: String?) : Origin, Serializable

data class CompositeOrigin(
    val elements: List<Origin>,
    override val range: Range?,
    override val sourceText: String?
) : Origin, Serializable

interface Destination

data class CompositeDestination(val elements: List<Destination>) : Destination, Serializable
data class TextFileDestination(val range: Range?) : Destination, Serializable

/**
 * The Abstract Syntax Tree will be constituted by instances of Node.
 *
 * It implements Origin as it could be the source of a AST-to-AST transformation, so the node itself can be
 * the Origin of another node.
 */
open class Node() : Origin, Destination, Serializable {

    @Internal
    protected var rangeOverride: Range? = null

    constructor(range: Range?) : this() {
        this.range = range
    }

    constructor(origin: Origin?) : this() {
        if (origin != null) {
            this.origin = origin
        }
    }

    @Internal
    open val nodeType: String
        get() = this::class.qualifiedName!!

    /**
     * The properties of this AST nodes, including attributes, children, and references.
     */
    @property:Internal
    open val properties: List<PropertyDescription>
        get() = try {
            nodeProperties.map { PropertyDescription.buildFor(it, this) }
        } catch (e: Throwable) {
            throw RuntimeException("Issue while getting properties of node ${this::class.qualifiedName}", e)
        }.also { properties ->
            val alreadyFound = mutableSetOf<String>()
            properties.forEach { property ->
                val name = property.name
                if (alreadyFound.contains(name)) {
                    throw IllegalStateException("Duplicate property with name $name")
                } else {
                    alreadyFound.add(name)
                }
            }
        }

    /**
     * The node from which this AST Node has been generated, if any.
     */
    @property:Internal
    var origin: Origin? = null

    /**
     * The parent node, if any.
     */
    @property:Internal
    var parent: Node? = null

    /**
     * The range of this node in the source text.
     * If a range has been provided when creating this node, it is returned.
     * Otherwise, the value of this property is the range of the origin, if any.
     */
    @property:Internal
    override var range: Range?
        get() = rangeOverride ?: origin?.range
        set(range) {
            this.rangeOverride = range
        }

    @property:Internal
    override val source: Source?
        get() = origin?.source

    fun detach(keepRange: Boolean = true, keepSourceText: Boolean = false) {
        val existingOrigin = origin
        if (existingOrigin != null) {
            if (keepRange || keepSourceText) {
                this.origin = SimpleOrigin(
                    if (keepRange) existingOrigin.range else null,
                    if (keepSourceText) existingOrigin.sourceText else null
                )
            } else {
                this.origin = null
            }
            if (existingOrigin is Node && existingOrigin.destination == this) {
                existingOrigin.destination = null
            }
        }
    }

    /**
     * Tests whether the given range is contained in the interval represented by this object.
     * @param range the range
     */
    fun contains(range: Range?): Boolean {
        return this.range?.contains(range) ?: false
    }

    /**
     * Tests whether the given range overlaps the interval represented by this object.
     * @param range the range
     */
    fun overlaps(range: Range?): Boolean {
        return this.range?.overlaps(range) ?: false
    }

    /**
     * The source text for this node
     */
    @Internal
    override val sourceText: String?
        get() = origin?.sourceText

    @Internal
    var destination: Destination? = null

    /**
     * This must be final because otherwise data classes extending this will automatically generate
     * their own implementation. If Link properties are present it could lead to stack overflows in case
     * of circular graphs.
     */
    final override fun toString(): String {
        return "${this.nodeType}(${properties.joinToString(", ") { "${it.name}=${it.valueToString()}" }})"
    }

    @property:Internal
    val observers: MutableList<Observer<in Node>> = mutableListOf()
    fun registerObserver(observer: Observer<*>) {
        observers.add(observer as Observer<in Node>)
    }

    fun unregisterObserver(observer: Observer<in Node>) {
        observers.remove(observer)
    }

    protected fun notifyOfPropertyChange(propertyName: String, oldValue: Any?, newValue: Any?) {
        observers.forEach {
            it.receivePropertyChangeNotification(this, propertyName, oldValue, newValue)
        }
    }
}

fun <N : Node> N.withRange(range: Range?): N {
    this.range = range
    return this
}

fun <N : Node> N.withOrigin(origin: Origin?): N {
    this.origin = if (origin == this) { null } else { origin }
    return this
}

val <T : Any> Class<T>.nodeProperties: Collection<KProperty1<T, *>>
    get() = this.kotlin.nodeProperties
val <T : Any> KClass<T>.nodeProperties: Collection<KProperty1<T, *>>
    get() = memberProperties.asSequence()
        .filter { it.visibility == KVisibility.PUBLIC }
        .filter { it.findAnnotation<Derived>() == null }
        .filter { it.findAnnotation<Internal>() == null }
        .filter { it.findAnnotation<Link>() == null }
        .toList()

/**
 * @return all properties of this node that are considered AST properties.
 */
val <T : Node> T.nodeProperties: Collection<KProperty1<T, *>>
    get() = this.javaClass.nodeProperties

/**
 * Use this to mark properties that are internal, i.e., they are used for bookkeeping and are not part of the model,
 * so that they will not be considered branches of the AST.
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Internal

/**
 * Use this to mark all relations which are secondary, i.e., they are calculated from other relations,
 * so that they will not be considered branches of the AST.
 */
annotation class Derived

/**
 * Use this to mark all the properties that return a Node or a list of Nodes which are not
 * contained by the Node having the properties. In other words: they are just references.
 * This will prevent them from being considered branches of the AST.
 */
annotation class Link

/**
 * Use this to mark something that does not inherit from Node as a node, so it will be included in the AST.
 */
annotation class NodeType
