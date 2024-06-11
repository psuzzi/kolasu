package com.strumenta.kolasu.lionweb

import io.lionweb.lioncore.java.model.ClassifierInstanceUtils

fun LWNode.getPropertyValueByName(propertyName: String) =
    ClassifierInstanceUtils.getPropertyValueByName(this, propertyName)

fun LWNode.getChildrenByContainmentName(propertyName: String) =
    ClassifierInstanceUtils.getChildrenByContainmentName(this, propertyName)

fun LWNode.getReferenceValueByName(propertyName: String) =
    ClassifierInstanceUtils.getReferenceValueByName(this, propertyName)

val LWNode.children
    get() = ClassifierInstanceUtils.getChildren(this)
