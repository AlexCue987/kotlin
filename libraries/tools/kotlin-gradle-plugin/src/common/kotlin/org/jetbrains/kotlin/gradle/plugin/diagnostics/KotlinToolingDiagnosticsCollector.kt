/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin.diagnostics

import org.gradle.api.InvalidUserCodeException
import org.gradle.api.Project
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.jetbrains.kotlin.gradle.utils.registerClassLoaderScopedBuildService
import java.util.*
import java.util.concurrent.ConcurrentHashMap

private typealias ToolingDiagnosticId = String
private typealias GradleProjectPath = String

internal abstract class KotlinToolingDiagnosticsCollector : BuildService<BuildServiceParameters.None> {
    private val diagnosticsFromProject: MutableMap<GradleProjectPath, MutableList<ToolingDiagnostic>> = ConcurrentHashMap()
    private val reportedIds: MutableSet<ToolingDiagnosticId> = Collections.newSetFromMap(ConcurrentHashMap())

    fun getDiagnosticsForProject(project: Project): Collection<ToolingDiagnostic> = diagnosticsFromProject[project.path].orEmpty()
    fun getAllDiagnostics(): Collection<ToolingDiagnostic> = diagnosticsFromProject.values.flatten()

    fun report(project: Project, diagnostic: ToolingDiagnostic) {
        saveDiagnostic(project, diagnostic)
    }

    fun reportOncePerGradleProject(fromProject: Project, diagnostic: ToolingDiagnostic, key: ToolingDiagnosticId = diagnostic.id) {
        if (reportedIds.add("${fromProject.path}#$key")) {
            saveDiagnostic(fromProject, diagnostic)
        }
    }

    fun reportOncePerGradleBuild(fromProject: Project, diagnostic: ToolingDiagnostic, key: ToolingDiagnosticId = diagnostic.id) {
        if (reportedIds.add(":#$key")) {
            saveDiagnostic(fromProject, diagnostic)
        }
    }

    private fun saveDiagnostic(project: Project, diagnostic: ToolingDiagnostic) {
        if (diagnostic.severity == ToolingDiagnostic.Severity.FATAL) {
            throw InvalidUserCodeException(diagnostic.message)
        }
        diagnosticsFromProject.compute(project.path) { _, previousListIfAny ->
            previousListIfAny?.apply { add(diagnostic) } ?: mutableListOf(diagnostic)
        }
    }
}

internal val Project.kotlinToolingDiagnosticsCollector: KotlinToolingDiagnosticsCollector
    get() = gradle.registerClassLoaderScopedBuildService(KotlinToolingDiagnosticsCollector::class).get()

internal fun Project.reportDiagnostic(diagnostic: ToolingDiagnostic) {
    kotlinToolingDiagnosticsCollector.report(this, diagnostic)
}

internal fun Project.reportDiagnosticOncePerProject(diagnostic: ToolingDiagnostic, key: ToolingDiagnosticId = diagnostic.id) {
    kotlinToolingDiagnosticsCollector.reportOncePerGradleProject(this, diagnostic, key)
}

internal fun Project.reportDiagnosticOncePerBuild(diagnostic: ToolingDiagnostic, key: ToolingDiagnosticId = diagnostic.id) {
    kotlinToolingDiagnosticsCollector.reportOncePerGradleBuild(this, diagnostic, key)
}
