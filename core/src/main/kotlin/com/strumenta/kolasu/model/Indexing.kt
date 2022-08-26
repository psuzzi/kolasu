package com.strumenta.kolasu.model

import java.util.IdentityHashMap

fun interface IdProvider {
    fun getId(node: Node): String?
}

class SequentialIdProvider(private var counter: Int = 0) : IdProvider {
    override fun getId(node: Node): String? {
        return "${this.counter++}"
    }
}

/**
 * @param walker a function that generates a sequence of nodes. By default this is the depth-first "walk" method.
 * @param idProvider a provider that takes a node and returns a String identifier representing it
 * For post-order traversal, take "walkLeavesFirst".
 * @return walks the whole AST starting from the given node and associates each visited node with a generated id
 **/
fun Node.computeIds(
    walker: (Node) -> Sequence<Node> = Node::walk,
    idProvider: IdProvider = SequentialIdProvider()
): IdentityHashMap<Node, String> {
    val idsMap = IdentityHashMap<Node, String>()
    walker.invoke(this).forEach {
        val id = idProvider.getId(it)
        if (id != null) idsMap[it] = id
    }
    return idsMap
}
