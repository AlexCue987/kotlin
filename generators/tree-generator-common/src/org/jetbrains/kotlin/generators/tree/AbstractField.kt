/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.generators.tree

abstract class AbstractField : Importable {

    abstract val name: String

    open val typeRef: TypeRef
        get() = type(packageName!!, type).withArgs(*arguments.toTypedArray()).copy(nullable = nullable)

    open val arguments = mutableListOf<TypeRef>()

    abstract val nullable: Boolean

    abstract val isVolatile: Boolean

    abstract val isFinal: Boolean

    abstract val isLateinit: Boolean

    abstract val isParameter: Boolean

    open val arbitraryImportables: MutableList<Importable> = mutableListOf()

    open var optInAnnotation: ArbitraryImportable? = null

    abstract val isMutable: Boolean
    open val withGetter: Boolean get() = false
    open val customSetter: String? get() = null

    var fromParent: Boolean = false

    override fun toString(): String {
        return name
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null) return false
        if (javaClass != other.javaClass) return false
        other as AbstractField
        return name == other.name
    }

    override fun hashCode(): Int {
        return name.hashCode()
    }
}