/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

// This file was generated automatically. See compiler/fir/tree/tree-generator/Readme.md.
// DO NOT MODIFY IT MANUALLY.

@file:Suppress("DuplicatedCode", "unused")

package org.jetbrains.kotlin.fir.builder

import kotlin.contracts.*
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.fir.FirLabel
import org.jetbrains.kotlin.fir.builder.FirBuilderDsl
import org.jetbrains.kotlin.fir.impl.FirLabelImpl
import org.jetbrains.kotlin.fir.visitors.*

@FirBuilderDsl
class FirLabelBuilder {
    var source: KtSourceElement? = null
    lateinit var name: String

    fun build(): FirLabel {
        return FirLabelImpl(
            source,
            name,
        )
    }

}

@OptIn(ExperimentalContracts::class)
inline fun buildLabel(init: FirLabelBuilder.() -> Unit): FirLabel {
    contract {
        callsInPlace(init, kotlin.contracts.InvocationKind.EXACTLY_ONCE)
    }
    return FirLabelBuilder().apply(init).build()
}
