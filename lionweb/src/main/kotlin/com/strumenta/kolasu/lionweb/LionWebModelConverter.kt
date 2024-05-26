package com.strumenta.kolasu.lionweb

import com.strumenta.kolasu.ids.IDGenerationException
import com.strumenta.kolasu.ids.NodeIdProvider
import com.strumenta.kolasu.language.KolasuLanguage
import com.strumenta.kolasu.model.FileSource
import com.strumenta.kolasu.model.Multiplicity
import com.strumenta.kolasu.model.NodeLike
import com.strumenta.kolasu.model.Point
import com.strumenta.kolasu.model.PossiblyNamed
import com.strumenta.kolasu.model.Range
import com.strumenta.kolasu.model.ReferenceValue
import com.strumenta.kolasu.model.Source
import com.strumenta.kolasu.model.allFeatures
import com.strumenta.kolasu.model.asContainment
import com.strumenta.kolasu.model.assignParents
import com.strumenta.kolasu.model.containingContainment
import com.strumenta.kolasu.model.indexInContainingProperty
import com.strumenta.kolasu.model.isAttribute
import com.strumenta.kolasu.model.isContainment
import com.strumenta.kolasu.model.isReference
import com.strumenta.kolasu.model.nodeOriginalProperties
import com.strumenta.kolasu.traversing.walk
import io.lionweb.lioncore.java.language.Classifier
import io.lionweb.lioncore.java.language.Concept
import io.lionweb.lioncore.java.language.Containment
import io.lionweb.lioncore.java.language.Enumeration
import io.lionweb.lioncore.java.language.Language
import io.lionweb.lioncore.java.language.LionCoreBuiltins
import io.lionweb.lioncore.java.language.PrimitiveType
import io.lionweb.lioncore.java.language.Property
import io.lionweb.lioncore.java.language.Reference
import io.lionweb.lioncore.java.model.Node
import io.lionweb.lioncore.java.model.impl.DynamicNode
import io.lionweb.lioncore.java.model.impl.EnumerationValue
import io.lionweb.lioncore.java.model.impl.EnumerationValueImpl
import io.lionweb.lioncore.java.model.impl.ProxyNode
import io.lionweb.lioncore.java.serialization.JsonSerialization
import io.lionweb.lioncore.java.serialization.PrimitiveValuesSerialization.PrimitiveDeserializer
import io.lionweb.lioncore.java.serialization.PrimitiveValuesSerialization.PrimitiveSerializer
import io.lionweb.lioncore.java.utils.CommonChecks
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KParameter
import kotlin.reflect.full.primaryConstructor
import io.lionweb.lioncore.java.model.ReferenceValue as LWReferenceValue

interface PrimitiveValueSerialization<E> {
    fun serialize(value: E): String

    fun deserialize(serialized: String): E
}

/**
 * This class is able to convert between Kolasu and LionWeb models, tracking the mapping.
 *
 * This class is thread-safe.
 *
 * @param nodeIdProvider logic to be used to associate IDs to Kolasu nodes when exporting them to LionWeb
 */
class LionWebModelConverter(
    var nodeIdProvider: NodeIdProvider = StructuralLionWebNodeIdProvider(),
    initialLanguageConverter: LionWebLanguageConverter = LionWebLanguageConverter(),
) {
    private val languageConverter = initialLanguageConverter

    /**
     * We mostly map Kolasu Nodes to LionWeb Nodes, but we also map things that are not Kolasu Nodes but are nodes
     * for LionWeb (this used to be the case for Positions and Points).
     */
    private val nodesMapping = BiMap<Any, LWNode>(usingIdentity = true)
    private val primitiveValueSerializations = ConcurrentHashMap<KClass<*>, PrimitiveValueSerialization<*>>()

    fun clearNodesMapping() {
        nodesMapping.clear()
    }

    fun <E : Any> registerPrimitiveValueSerialization(
        kClass: KClass<E>,
        primitiveValueSerialization: PrimitiveValueSerialization<E>,
    ) {
        primitiveValueSerializations[kClass] = primitiveValueSerialization
    }

    fun correspondingLanguage(kolasuLanguage: KolasuLanguage): Language {
        synchronized(languageConverter) {
            return languageConverter.correspondingLanguage(kolasuLanguage)
        }
    }

    fun exportLanguageToLionWeb(kolasuLanguage: KolasuLanguage): Language {
        synchronized(languageConverter) {
            return languageConverter.exportToLionWeb(kolasuLanguage)
        }
    }

    fun associateLanguages(
        lwLanguage: Language,
        kolasuLanguage: KolasuLanguage,
    ) {
        synchronized(languageConverter) {
            this.languageConverter.associateLanguages(lwLanguage, kolasuLanguage)
        }
    }

    fun exportModelToLionWeb(
        kolasuTree: KNode,
        nodeIdProvider: NodeIdProvider = this.nodeIdProvider,
        considerParent: Boolean = true,
    ): LWNode {
        kolasuTree.assignParents()
        val myIDManager =
            object {
                fun nodeId(kNode: KNode): String {
                    return nodeIdProvider.id(kNode)
                }
            }

        if (!nodesMapping.containsA(kolasuTree)) {
            kolasuTree.walk().forEach { kNode ->
                if (!nodesMapping.containsA(kNode)) {
                    val lwNode = DynamicNode(myIDManager.nodeId(kNode), findConcept(kNode))
                    associateNodes(kNode, lwNode)
                }
            }
            kolasuTree.walk().forEach { kNode ->
                val lwNode = nodesMapping.byA(kNode)!!
                if (!CommonChecks.isValidID(lwNode.id)) {
                    throw RuntimeException(
                        "Cannot export AST to LionWeb as we got an invalid Node ID: ${lwNode.id}. " +
                            "It was produced while exporting this Kolasu Node: $kNode",
                    )
                }
                val kFeatures = kNode.javaClass.kotlin.allFeatures()
                lwNode.concept.allFeatures().forEach { feature ->
                    when (feature) {
                        is Property -> {
                            if (feature == StarLasuLWLanguage.ASTNodeRange) {
                                lwNode.setPropertyValue(StarLasuLWLanguage.ASTNodeRange, kNode.range)
                            } else {
                                val kAttribute =
                                    kFeatures.find { it.name == feature.name }
                                        as? com.strumenta.kolasu.language.Property
                                        ?: throw IllegalArgumentException(
                                            "Property ${feature.name} not found in $kNode",
                                        )
                                val kValue = kNode.getPropertySimpleValue<Any>(kAttribute)
                                if (kValue is Enum<*>) {
                                    val kClass: EnumKClass = kValue::class as EnumKClass
                                    val enumeration =
                                        languageConverter.getKolasuClassesToEnumerationsMapping()[kClass]
                                            ?: throw IllegalStateException("No enumeration for enum class $kClass")
                                    val enumerationLiteral =
                                        enumeration.literals.find { it.name == kValue.name }
                                            ?: throw IllegalStateException(
                                                "No enumeration literal with name ${kValue.name} " +
                                                    "in enumeration $enumeration",
                                            )
                                    lwNode.setPropertyValue(feature, EnumerationValueImpl(enumerationLiteral))
                                } else {
                                    lwNode.setPropertyValue(feature, kValue)
                                }
                            }
                        }

                        is Containment -> {
                            try {
                                val kContainment =
                                    (
                                        kFeatures.find { it.name == feature.name } ?: throw IllegalStateException(
                                            "Cannot find containment for ${feature.name} when considering " +
                                                "node $kNode",
                                        )
                                    )
                                        as com.strumenta.kolasu.language.Containment
                                val kValue = kNode.getChildren(kContainment)
                                kValue.forEach { kChild ->
                                    val lwChild = nodesMapping.byA(kChild)!!
                                    lwNode.addChild(feature, lwChild)
                                }
                            } catch (e: Exception) {
                                throw RuntimeException("Issue while processing containment ${feature.name}", e)
                            }
                        }
                        is Reference -> {
                            val kReference =
                                kFeatures.find { it.name == feature.name }
                                    as com.strumenta.kolasu.language.Reference
                            val kValue = kNode.getReference(kReference)
                            if (kValue == null) {
                                lwNode.addReferenceValue(feature, null)
                            } else {
                                when {
                                    kValue.isRetrieved -> {
                                        val kReferred =
                                            (
                                                kValue.referred ?: throw IllegalStateException(
                                                    "Reference " +
                                                        "retrieved but referred is empty",
                                                )
                                            ) as KNode
                                        // We may have a reference to a Kolasu Node that we are not exporting, and for
                                        // which we have therefore no LionWeb node. In that case, if we have the
                                        // identifier, we can produce a ProxyNode instead
                                        val lwReferred: Node =
                                            nodesMapping.byA(kReferred) ?: ProxyNode(
                                                kValue.identifier
                                                    ?: throw java.lang.IllegalStateException(
                                                        "Identifier of reference target " +
                                                            "value not set. Referred: $kReferred, " +
                                                            "reference holder: $kNode",
                                                    ),
                                            )
                                        lwNode.addReferenceValue(feature, LWReferenceValue(lwReferred, kValue.name))
                                    }
                                    kValue.isResolved -> {
                                        // This is tricky, as we need to set a LW Node, but we have just an identifier...
                                        val lwReferred: Node =
                                            DynamicNode(kValue.identifier!!, LionCoreBuiltins.getNode())
                                        lwNode.addReferenceValue(feature, LWReferenceValue(lwReferred, kValue.name))
                                    }
                                    else -> {
                                        lwNode.addReferenceValue(feature, LWReferenceValue(null, kValue.name))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val result = nodesMapping.byA(kolasuTree)!!
        if (considerParent && kolasuTree.parent != null) {
            val parentNodeId =
                try {
                    nodeIdProvider.id(kolasuTree.parent!!)
                } catch (e: IDGenerationException) {
                    throw IDGenerationException(
                        "Cannot produce an ID for ${kolasuTree.parent}, which was needed to " +
                            "create a ProxyNode",
                        e,
                    )
                }
            (result as DynamicNode).parent = ProxyNode(parentNodeId)
        }
        return result
    }

    fun importModelFromLionWeb(lwTree: LWNode): Any {
        val referencesPostponer = ReferencesPostponer()
        lwTree.thisAndAllDescendants().reversed().forEach { lwNode ->
            val kClass =
                synchronized(languageConverter) {
                    languageConverter.correspondingKolasuClass(lwNode.concept)
                        ?: throw RuntimeException(
                            "We do not have StarLasu AST class for " +
                                "LIonWeb Concept ${lwNode.concept}",
                        )
                }
            try {
                val instantiated = instantiate(kClass, lwNode, referencesPostponer)
                if (instantiated is KNode) {
                    instantiated.assignParents()
                }
                associateNodes(instantiated, lwNode)
            } catch (e: RuntimeException) {
                throw RuntimeException("Issue instantiating $kClass from LionWeb node $lwNode", e)
            }
        }
        lwTree.thisAndAllDescendants().forEach { lwNode ->
            val kNode = nodesMapping.byB(lwNode)!!
            if (kNode is KNode) {
                val lwRange = lwNode.getPropertyValue(StarLasuLWLanguage.ASTNodeRange)
                if (lwRange != null) {
                    kNode.range = lwRange as Range
                }
            }
        }
        referencesPostponer.populateReferences(nodesMapping)
        return nodesMapping.byB(lwTree)!!
    }

    fun prepareJsonSerialization(
        jsonSerialization: JsonSerialization =
            JsonSerialization.getStandardSerialization(),
    ): JsonSerialization {
        jsonSerialization.primitiveValuesSerialization.registerSerializer(
            StarLasuLWLanguage.char.id,
        ) { value -> "$value" }
        jsonSerialization.primitiveValuesSerialization.registerDeserializer(
            StarLasuLWLanguage.char.id,
        ) { serialized -> serialized[0] }
        val pointSerializer: PrimitiveSerializer<Point> =
            PrimitiveSerializer<Point> { value ->
                if (value == null) {
                    return@PrimitiveSerializer null
                }
                "L${value.line}:${value.column}"
            }
        val pointDeserializer: PrimitiveDeserializer<Point> =
            PrimitiveDeserializer<Point> { serialized ->
                if (serialized == null) {
                    return@PrimitiveDeserializer null
                }
                require(serialized.startsWith("L"))
                require(serialized.removePrefix("L").isNotEmpty())
                val parts = serialized.removePrefix("L").split(":")
                require(parts.size == 2)
                Point(parts[0].toInt(), parts[1].toInt())
            }
        jsonSerialization.primitiveValuesSerialization.registerSerializer(
            StarLasuLWLanguage.Point.id,
            pointSerializer,
        )
        jsonSerialization.primitiveValuesSerialization.registerDeserializer(
            StarLasuLWLanguage.Point.id,
            pointDeserializer,
        )
        jsonSerialization.primitiveValuesSerialization.registerSerializer(
            StarLasuLWLanguage.Range.id,
        ) { value ->
            "${pointSerializer.serialize((value as Range).start)} to ${pointSerializer.serialize(value.end)}"
        }
        jsonSerialization.primitiveValuesSerialization.registerDeserializer(
            StarLasuLWLanguage.Range.id,
        ) { serialized ->
            if (serialized == null) {
                null
            } else {
                val parts = serialized.split(" to ")
                require(parts.size == 2)
                Range(pointDeserializer.deserialize(parts[0]), pointDeserializer.deserialize(parts[1]))
            }
        }
        synchronized(languageConverter) {
            languageConverter.knownLWLanguages().forEach {
                jsonSerialization.primitiveValuesSerialization.registerLanguage(it)
                jsonSerialization.classifierResolver.registerLanguage(it)
            }
            languageConverter.knownKolasuLanguages().forEach { kolasuLanguage ->
                kolasuLanguage.primitiveClasses.forEach { primitiveClass ->
                    if (primitiveValueSerializations.containsKey(primitiveClass)) {
                        val lwPrimitiveType: PrimitiveType =
                            languageConverter
                                .getKolasuClassesToPrimitiveTypesMapping()[primitiveClass]
                                ?: throw IllegalStateException(
                                    "No Primitive Type found associated to primitive value class " +
                                        "${primitiveClass.qualifiedName}",
                                )
                        val serializer =
                            primitiveValueSerializations[primitiveClass]!!
                                as PrimitiveValueSerialization<Any>
                        jsonSerialization.primitiveValuesSerialization.registerSerializer(
                            lwPrimitiveType.id!!,
                        ) { value -> serializer.serialize(value) }

                        jsonSerialization.primitiveValuesSerialization.registerDeserializer(
                            lwPrimitiveType.id!!,
                        ) { serialized -> serializer.deserialize(serialized) }
                    }
                }
            }
        }
        return jsonSerialization
    }

    /**
     * Deserialize nodes, taking into accaount the known languages.
     */
    fun deserializeToNodes(
        json: String,
        useDynamicNodesIfNeeded: Boolean = true,
    ): List<LWNode> {
        val js = prepareJsonSerialization()
        if (useDynamicNodesIfNeeded) {
            js.enableDynamicNodes()
        }
        return js.deserializeToNodes(json)
    }

    fun knownLWLanguages(): Set<LWLanguage> {
        synchronized(languageConverter) {
            return languageConverter.knownLWLanguages()
        }
    }

    fun knownKolasuLanguages(): Set<KolasuLanguage> {
        synchronized(languageConverter) {
            return languageConverter.knownKolasuLanguages()
        }
    }

    fun getKolasuClassesToClassifiersMapping(): Map<KClass<*>, Classifier<*>> {
        synchronized(languageConverter) {
            return languageConverter.getKolasuClassesToClassifiersMapping()
        }
    }

    fun getClassifiersToKolasuClassesMapping(): Map<Classifier<*>, KClass<*>> {
        synchronized(languageConverter) {
            return languageConverter.getClassifiersToKolasuClassesMapping()
        }
    }

    /**
     * Track reference values, so that we can populate them once the nodes are instantiated.
     */
    private class ReferencesPostponer {
        private val values = IdentityHashMap<com.strumenta.kolasu.model.ReferenceValue<PossiblyNamed>, LWNode?>()

        fun registerPostponedReference(
            referenceValue: com.strumenta.kolasu.model.ReferenceValue<PossiblyNamed>,
            referred: LWNode?,
        ) {
            values[referenceValue] = referred
        }

        fun populateReferences(nodesMapping: BiMap<Any, LWNode>) {
            values.forEach { entry ->
                if (entry.value == null) {
                    entry.key.referred = null
                } else {
                    if (entry.value is ProxyNode) {
                        entry.key.identifier = (entry.value as ProxyNode).id
                    } else {
                        entry.key.referred = nodesMapping.byB(entry.value as LWNode)!! as PossiblyNamed
                    }
                }
            }
        }
    }

    private fun attributeValue(
        data: LWNode,
        property: Property,
    ): Any? {
        val propValue = data.getPropertyValue(property)
        val value =
            if (property.type is Enumeration && propValue != null) {
                val enumerationLiteral =
                    if (propValue is EnumerationValue) {
                        propValue.enumerationLiteral
                    } else {
                        throw java.lang.IllegalStateException(
                            "Property value of property of enumeration type is " +
                                "not an EnumerationValue. It is instead " + propValue,
                        )
                    }
                val enumKClass =
                    synchronized(languageConverter) {
                        languageConverter
                            .getEnumerationsToKolasuClassesMapping()[enumerationLiteral.enumeration]
                            ?: throw java.lang.IllegalStateException(
                                "Cannot find enum class for enumeration " +
                                    "${enumerationLiteral.enumeration?.name}",
                            )
                    }
                val entries = enumKClass.java.enumConstants
                entries.find { it.name == enumerationLiteral.name }
                    ?: throw IllegalStateException(
                        "Cannot find enum constant named ${enumerationLiteral.name} in enum " +
                            "class ${enumKClass.qualifiedName}",
                    )
            } else {
                propValue
            }
        return value
    }

    private fun containmentValue(
        data: LWNode,
        containment: Containment,
    ): Any? {
        val lwChildren = data.getChildren(containment)
        if (containment.isMultiple) {
            val kChildren = lwChildren.map { nodesMapping.byB(it)!! }
            return kChildren
        } else {
            // Given we navigate the tree in reverse the child should have been already
            // instantiated
            val lwChild: Node? =
                when (lwChildren.size) {
                    0 -> {
                        null
                    }

                    1 -> {
                        lwChildren.first()
                    }

                    else -> {
                        throw IllegalStateException()
                    }
                }
            val kChild =
                if (lwChild == null) {
                    return null
                } else {
                    (
                        return nodesMapping.byB(lwChild)
                            ?: throw IllegalStateException(
                                "Unable to find Kolasu Node corresponding to $lwChild",
                            )
                    )
                }
        }
    }

    private fun referenceValue(
        data: LWNode,
        reference: Reference,
        referencesPostponer: ReferencesPostponer,
        currentValue: ReferenceValue<PossiblyNamed>? = null,
    ): Any? {
        val referenceValues = data.getReferenceValues(reference)
        return when {
            referenceValues.size > 1 -> {
                throw IllegalStateException()
            }
            referenceValues.size == 0 -> {
                null
            }
            referenceValues.size == 1 -> {
                val rf = referenceValues.first()
                val referenceByName = currentValue ?: ReferenceValue<PossiblyNamed>(rf.resolveInfo!!, null)
                referencesPostponer.registerPostponedReference(referenceByName, rf.referred)
                referenceByName
            }
            else -> throw java.lang.IllegalStateException()
        }
    }

    private fun <T : Any> instantiate(
        kClass: KClass<T>,
        data: Node,
        referencesPostponer: ReferencesPostponer,
    ): T {
        val constructor: KFunction<Any> =
            when {
                kClass.constructors.size == 1 -> {
                    kClass.constructors.first()
                }
                kClass.primaryConstructor != null -> {
                    kClass.primaryConstructor!!
                }
                else -> {
                    TODO()
                }
            }
        val params = mutableMapOf<KParameter, Any?>()
        constructor.parameters.forEach { param ->
            val feature = data.concept.getFeatureByName(param.name!!)
            if (feature == null) {
                throw java.lang.IllegalStateException(
                    "We could not find a feature named as the parameter ${param.name} " +
                        "on class $kClass",
                )
            } else {
                when (feature) {
                    is Property -> {
                        params[param] = attributeValue(data, feature)
                    }

                    is Reference -> {
                        params[param] = referenceValue(data, feature, referencesPostponer)
                    }

                    is Containment -> {
                        params[param] = containmentValue(data, feature)
                    }

                    else -> throw IllegalStateException()
                }
            }
        }

        val kNode =
            try {
                constructor.callBy(params) as T
            } catch (e: Exception) {
                throw RuntimeException(
                    "Issue instantiating using constructor $constructor with params " +
                        "${params.map { "${it.key.name}=${it.value}" }}",
                    e,
                )
            }

        val propertiesNotSetAtConstructionTime =
            kClass.nodeOriginalProperties.filter { prop ->
                params.keys.none { param ->
                    param.name == prop.name
                }
            }
        propertiesNotSetAtConstructionTime.forEach { property ->
            val feature = data.concept.getFeatureByName(property.name!!)
            if (property !is KMutableProperty<*>) {
                if (property.isContainment() && property.asContainment().multiplicity == Multiplicity.MANY) {
                    val currentValue = property.get(kNode) as MutableList<KNode>
                    currentValue.clear()
                    val valueToSet = containmentValue(data, feature as Containment) as List<KNode>
                    currentValue.addAll(valueToSet)
                } else if (property.isReference()) {
                    val currentValue = property.get(kNode) as ReferenceValue<PossiblyNamed>
                    val valueToSet =
                        referenceValue(data, feature as Reference, referencesPostponer, currentValue)
                            as ReferenceValue<PossiblyNamed>
                    currentValue.name = valueToSet.name
                    currentValue.referred = valueToSet.referred
                    currentValue.identifier = valueToSet.identifier
                } else {
                    throw java.lang.IllegalStateException(
                        "Cannot set this property, as it is immutable: ${property.name} on $kNode. " +
                            "The properties set at construction time are: " +
                            params.keys.joinToString(", ") { it.name ?: "<UNNAMED>" },
                    )
                }
            } else {
                when {
                    property.isAttribute() -> {
                        val value = attributeValue(data, feature as Property)
                        property.setter.call(kNode, value)
                    }
                    property.isReference() -> {
                        val valueToSet =
                            referenceValue(data, feature as Reference, referencesPostponer)
                                as ReferenceValue<PossiblyNamed>
                        property.setter.call(kNode, valueToSet)
                    }
                    property.isContainment() -> {
                        try {
                            val valueToSet = containmentValue(data, feature as Containment)
                            property.setter.call(kNode, valueToSet)
                        } catch (e: java.lang.Exception) {
                            throw RuntimeException("Unable to set containment $feature on node $kNode", e)
                        }
                    }
                }
            }
        }

        return kNode
    }

    private fun findConcept(kNode: NodeLike): Concept {
        return synchronized(languageConverter) { languageConverter.correspondingConcept(kNode.nodeType) }
    }

    private fun nodeID(
        kNode: NodeLike,
        customSourceId: String? = null,
    ): String {
        return "${customSourceId ?: kNode.source.id}_${kNode.positionalID}"
    }

    private fun associateNodes(
        kNode: Any,
        lwNode: LWNode,
    ) {
        nodesMapping.associate(kNode, lwNode)
    }
}

private val KNode.positionalID: String
    get() {
        return if (this.parent == null) {
            "root"
        } else {
            val cp = this.containingContainment()!!
            val postfix = if (cp.isMultiple) "${cp.name}_${this.indexInContainingProperty()!!}" else cp.name
            "${this.parent!!.positionalID}_$postfix"
        }
    }

private val Source?.id: String
    get() {
        return if (this == null) {
            "UNKNOWN_SOURCE"
        } else if (this is FileSource) {
            "file_${this.file.path.replace('.', '-').replace('/', '-')}"
        } else {
            TODO("Unable to generate ID for Source $this (${this.javaClass.canonicalName})")
        }
    }
