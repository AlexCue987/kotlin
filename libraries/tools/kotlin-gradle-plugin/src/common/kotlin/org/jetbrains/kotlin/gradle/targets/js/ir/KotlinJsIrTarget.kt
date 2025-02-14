/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.js.ir

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.*
import org.jetbrains.kotlin.gradle.plugin.AbstractKotlinTargetConfigurator.Companion.runTaskNameSuffix
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation.Companion.MAIN_COMPILATION_NAME
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinTargetComponent
import org.jetbrains.kotlin.gradle.plugin.KotlinTargetWithTests
import org.jetbrains.kotlin.gradle.plugin.PropertiesProvider
import org.jetbrains.kotlin.gradle.plugin.mpp.*
import org.jetbrains.kotlin.gradle.targets.js.JsAggregatingExecutionSource
import org.jetbrains.kotlin.gradle.targets.js.KotlinJsReportAggregatingTestRun
import org.jetbrains.kotlin.gradle.targets.js.KotlinWasmTargetType
import org.jetbrains.kotlin.gradle.targets.js.binaryen.BinaryenExec
import org.jetbrains.kotlin.gradle.targets.js.dsl.*
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrTargetConfigurator.Companion.configureJsDefaultOptions
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin
import org.jetbrains.kotlin.gradle.targets.js.npm.NpmResolverPlugin
import org.jetbrains.kotlin.gradle.targets.js.npm.npmProject
import org.jetbrains.kotlin.gradle.targets.js.typescript.TypeScriptValidationTask
import org.jetbrains.kotlin.gradle.tasks.*
import org.jetbrains.kotlin.gradle.utils.*
import org.jetbrains.kotlin.util.capitalizeDecapitalize.capitalizeAsciiOnly
import org.jetbrains.kotlin.util.capitalizeDecapitalize.toLowerCaseAsciiOnly
import org.jetbrains.kotlin.utils.addIfNotNull
import java.io.File
import javax.inject.Inject

abstract class KotlinJsIrTarget
@Inject
constructor(
    project: Project,
    platformType: KotlinPlatformType,
) :
    KotlinTargetWithBinaries<KotlinJsIrCompilation, KotlinJsBinaryContainer>(project, platformType),
    KotlinTargetWithTests<JsAggregatingExecutionSource, KotlinJsReportAggregatingTestRun>,
    KotlinJsTargetDsl,
    KotlinWasmJsTargetDsl,
    KotlinWasmWasiTargetDsl,
    KotlinJsSubTargetContainerDsl,
    KotlinWasmSubTargetContainerDsl {

    private val propertiesProvider = PropertiesProvider(project)
    override lateinit var testRuns: NamedDomainObjectContainer<KotlinJsReportAggregatingTestRun>
        internal set

    open var isMpp: Boolean? = null
        internal set

    override var wasmTargetType: KotlinWasmTargetType? = null
        internal set

    override var moduleName: String? = null
        set(value) {
            check(!isBrowserConfigured && !isNodejsConfigured) {
                "Please set moduleName before initialize browser() or nodejs()"
            }
            field = value
        }

    override val kotlinComponents: Set<KotlinTargetComponent> by lazy {
        val mainCompilation = compilations.getByName(KotlinCompilation.MAIN_COMPILATION_NAME)
        val usageContexts = createUsageContexts(mainCompilation).toMutableSet()

        val componentName =
            if (project.kotlinExtension is KotlinMultiplatformExtension)
                targetName
            else PRIMARY_SINGLE_COMPONENT_NAME

        usageContexts.addIfNotNull(
            createSourcesJarAndUsageContextIfPublishable(
                producingCompilation = mainCompilation,
                componentName = componentName,
                artifactNameAppendix = wasmDecamelizedDefaultNameOrNull() ?: dashSeparatedName(targetName.toLowerCaseAsciiOnly())
            )
        )

        val result = createKotlinVariant(componentName, mainCompilation, usageContexts)

        setOf(result)
    }

    override fun createKotlinVariant(
        componentName: String,
        compilation: KotlinCompilation<*>,
        usageContexts: Set<DefaultKotlinUsageContext>,
    ): KotlinVariant {
        return super.createKotlinVariant(componentName, compilation, usageContexts).apply {
            artifactTargetName = wasmDecamelizedDefaultNameOrNull() ?: componentName
        }
    }

    override fun createUsageContexts(producingCompilation: KotlinCompilation<*>): Set<DefaultKotlinUsageContext> {
        val usageContexts = super.createUsageContexts(producingCompilation)

        if (isMpp!!) return usageContexts

        return usageContexts +
                DefaultKotlinUsageContext(
                    compilation = compilations.getByName(MAIN_COMPILATION_NAME),
                    mavenScope = KotlinUsageContext.MavenScope.COMPILE,
                    dependencyConfigurationName = commonFakeApiElementsConfigurationName,
                    overrideConfigurationArtifacts = project.setProperty { emptyList() }
                )
    }

    internal val commonFakeApiElementsConfigurationName: String
        get() = lowerCamelCaseName(
            disambiguationClassifier,
            "commonFakeApiElements"
        )

    override val binaries: KotlinJsBinaryContainer
        get() = compilations.withType(KotlinJsIrCompilation::class.java)
            .named(MAIN_COMPILATION_NAME)
            .map { it.binaries }
            .get()

    private val runTaskName get() = lowerCamelCaseName(disambiguationClassifier, runTaskNameSuffix)
    val runTask: TaskProvider<Task>
        get() = project.locateOrRegisterTask(runTaskName) {
            it.description = "Run js on all configured platforms"
        }

    private val configureTestSideEffect: Unit by lazy {
        compilations.matching { it.name == KotlinCompilation.TEST_COMPILATION_NAME }
            .all { compilation ->
                compilation.binaries.executableIrInternal(compilation)
            }
    }

    private val commonLazyDelegate = lazy {
        NpmResolverPlugin.apply(project)
        compilations.all { compilation ->
            compilation.binaries
                .withType(JsIrBinary::class.java)
                .all { binary ->
                    val syncTask = registerCompileSync(binary)
                    binaryenReplaceInput[binary]?.invoke()
                    val tsValidationTask = registerTypeScriptCheckTask(binary)

                    binary.linkTask.configure {

                        it.finalizedBy(syncTask)

                        if (binary.generateTs) {
                            it.finalizedBy(tsValidationTask)
                        }
                    }
                }
        }
    }

    private val commonLazy by commonLazyDelegate

    private fun registerCompileSync(binary: JsIrBinary): TaskProvider<DefaultIncrementalSyncTask> {
        val compilation = binary.compilation
        val npmProject = compilation.npmProject

        return project.registerTask<DefaultIncrementalSyncTask>(
            binary.linkSyncTaskName
        ) { task ->
            task.from.from(
                binary.linkTask.flatMap { linkTask ->
                    linkTask.destinationDirectory.map { it.asFile }
                }
            )

            task.from.from(project.tasks.named(compilation.processResourcesTaskName))

            task.destinationDirectory.set(npmProject.dist)
        }
    }

    private fun registerTypeScriptCheckTask(binary: JsIrBinary): TaskProvider<TypeScriptValidationTask> {
        val linkTask = binary.linkTask
        val compilation = binary.compilation
        return project.registerTask(binary.validateGeneratedTsTaskName, listOf(compilation)) {
            it.inputDir.set(linkTask.flatMap { it.destinationDirectory })
            it.validationStrategy.set(
                when (binary.mode) {
                    KotlinJsBinaryMode.DEVELOPMENT -> propertiesProvider.jsIrGeneratedTypeScriptValidationDevStrategy
                    KotlinJsBinaryMode.PRODUCTION -> propertiesProvider.jsIrGeneratedTypeScriptValidationProdStrategy
                }
            )
        }
    }

    //Binaryen
    private var binaryenReplaceInput: MutableMap<JsIrBinary, () -> Unit> = mutableMapOf()

    override fun applyBinaryen(body: BinaryenExec.() -> Unit) {
        compilations.all {
            it.binaries.all { binary ->
                if (binary is ExecutableWasm) {
                    val binaryenExec = createBinaryen(binary, body)

                    if (
                        wasmTargetType == KotlinWasmTargetType.WASI && nodejsLazyDelegate.isInitialized() ||
                        project.locateTask<IncrementalSyncTask>(binary.linkSyncTaskName) != null
                    ) {
                        configureBinaryen(binary, binaryenExec)
                    } else {
                        binaryenReplaceInput[binary] = {
                            configureBinaryen(binary, binaryenExec)
                        }
                    }
                }
            }
        }
    }

    private fun createBinaryen(binary: ExecutableWasm, binaryenDsl: BinaryenExec.() -> Unit): TaskProvider<BinaryenExec> {
        val linkTask = binary.linkTask

        val compileWasmDestDir = linkTask.map {
            it.destinationDirectory
        }

        val compiledWasmFile = linkTask.map { link ->
            link.destinationDirectory.asFile.get().resolve(link.compilerOptions.moduleName.get() + ".wasm")
        }

        return BinaryenExec.create(binary.compilation, binary.optimizeTaskName) {
            val compilation = binary.compilation
            dependsOn(linkTask)
            inputFileProperty.fileProvider(compiledWasmFile)

            val outputDirectory: Provider<File> = binary.target.project.layout.buildDirectory
                .dir(COMPILE_SYNC)
                .map { it.dir(compilation.target.targetName) }
                .map { it.dir(compilation.name) }
                .map { it.dir(binary.name) }
                .map { it.dir("optimized") }
                .map { it.asFile }

            val outputFile = outputDirectory.map { it.resolve(compiledWasmFile.get().name) }

            outputFileProperty.fileProvider(
                outputFile
            )

            doLast {
                fs.copy {
                    it.from(compileWasmDestDir)
                    it.into(outputDirectory)
                    it.eachFile {
                        if (it.relativePath.getFile(outputDirectory.get()).exists()) {
                            it.exclude()
                        }
                    }
                }
            }

            binaryenDsl()
        }
    }

    private fun configureBinaryen(binary: JsIrBinary, binaryenExec: TaskProvider<BinaryenExec>) {
        if (wasmTargetType == KotlinWasmTargetType.WASI) {
            if (binary.compilation.isMain() && binary.mode == KotlinJsBinaryMode.PRODUCTION) {
                project.tasks.named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME).dependsOn(binaryenExec)
            }

            whenNodejsConfigured {
                testTask {
                    val name = binary.linkTask.flatMap { it.outputFileProperty.map { it.name } }
                    it.inputFileProperty.fileProvider(
                        binaryenExec.flatMap { it.outputFileProperty.map { it.asFile.parentFile.resolve(name.get()) } }
                    )
                    it.dependsOn(binaryenExec)
                }
            }
        } else {
            binary.linkSyncTask.configure {
                it.from.setFrom(binaryenExec.flatMap { it.outputFileProperty.map { it.asFile.parentFile } })
                it.dependsOn(binaryenExec)
            }
        }
    }

    //Browser
    private val browserLazyDelegate = lazy {
        commonLazy
        project.objects.newInstance(KotlinBrowserJsIr::class.java, this).also {
            it.configureSubTarget()
            browserConfiguredHandlers.forEach { handler ->
                handler(it)
            }
            browserConfiguredHandlers.clear()
        }
    }

    private val browserConfiguredHandlers = mutableListOf<KotlinJsBrowserDsl.() -> Unit>()

    override val browser by browserLazyDelegate

    override val isBrowserConfigured: Boolean
        get() = browserLazyDelegate.isInitialized()

    override fun browser(body: KotlinJsBrowserDsl.() -> Unit) {
        body(browser)
    }

    //node.js
    private val nodejsLazyDelegate = lazy {
        if (wasmTargetType != KotlinWasmTargetType.WASI) {
            commonLazy
        } else {
            NodeJsRootPlugin.apply(project.rootProject)
            compilations.all { compilation ->
                compilation.binaries.all { binary ->
                    binaryenReplaceInput[binary]?.invoke()
                }
            }
        }
        project.objects.newInstance(KotlinNodeJsIr::class.java, this).also {
            it.configureSubTarget()
            nodejsConfiguredHandlers.forEach { handler ->
                handler(it)
            }

            nodejsConfiguredHandlers.clear()
        }
    }

    private val nodejsConfiguredHandlers = mutableListOf<KotlinJsNodeDsl.() -> Unit>()

    override val nodejs by nodejsLazyDelegate

    override val isNodejsConfigured: Boolean
        get() = nodejsLazyDelegate.isInitialized()

    override fun nodejs(body: KotlinJsNodeDsl.() -> Unit) {
        body(nodejs)
    }

    //d8
    private val d8LazyDelegate = lazy {
        commonLazy
        project.objects.newInstance(KotlinD8Ir::class.java, this).also {
            it.configureSubTarget()
            d8ConfiguredHandlers.forEach { handler ->
                handler(it)
            }

            d8ConfiguredHandlers.clear()
        }
    }

    private val d8ConfiguredHandlers = mutableListOf<KotlinWasmD8Dsl.() -> Unit>()

    override val d8 by d8LazyDelegate

    override val isD8Configured: Boolean
        get() = d8LazyDelegate.isInitialized()

    private fun KotlinJsIrSubTarget.configureSubTarget() {
        configureTestSideEffect
        configure()
    }

    override fun d8(body: KotlinWasmD8Dsl.() -> Unit) {
        body(d8)
    }

    override fun whenBrowserConfigured(body: KotlinJsBrowserDsl.() -> Unit) {
        if (browserLazyDelegate.isInitialized()) {
            browser(body)
        } else {
            browserConfiguredHandlers += body
        }
    }

    override fun whenNodejsConfigured(body: KotlinJsNodeDsl.() -> Unit) {
        if (nodejsLazyDelegate.isInitialized()) {
            nodejs(body)
        } else {
            nodejsConfiguredHandlers += body
        }
    }

    override fun whenD8Configured(body: KotlinWasmD8Dsl.() -> Unit) {
        if (d8LazyDelegate.isInitialized()) {
            d8(body)
        } else {
            d8ConfiguredHandlers += body
        }
    }

    override fun useCommonJs() {
        compilations.all {
            it.kotlinOptions.configureCommonJsOptions()

            binaries
                .withType(JsIrBinary::class.java)
                .all {
                    it.linkTask.configure { linkTask ->
                        linkTask.kotlinOptions.configureCommonJsOptions()
                    }
                }
        }
    }

    override fun useEsModules() {
        compilations.all {
            it.kotlinOptions.configureEsModulesOptions()

            binaries
                .withType(JsIrBinary::class.java)
                .all {
                    it.linkTask.configure { linkTask ->
                        linkTask.kotlinOptions.configureEsModulesOptions()
                    }
                }
        }

    }

    private fun KotlinJsOptions.configureCommonJsOptions() {
        moduleKind = "commonjs"
        sourceMap = true
        sourceMapEmbedSources = "never"
    }

    private fun KotlinJsOptions.configureEsModulesOptions() {
        moduleKind = "es"
        sourceMap = true
        sourceMapEmbedSources = "never"
    }

    override fun generateTypeScriptDefinitions() {
        compilations
            .all {
                it.binaries
                    .withType(JsIrBinary::class.java)
                    .all {
                        it.generateTs = true
                        it.linkTask.configure { linkTask ->
                            linkTask.compilerOptions.freeCompilerArgs.add(GENERATE_D_TS)
                        }
                    }
            }
    }

    @Suppress("RedundantVisibilityModifier")
    @ExperimentalKotlinGradlePluginApi
    internal override val compilerOptions: KotlinJsCompilerOptions = project.objects
        .newInstance<KotlinJsCompilerOptionsDefault>()
        .apply {
            configureJsDefaultOptions(platformType)

            freeCompilerArgs.add(DISABLE_PRE_IR)
        }
}

fun KotlinJsIrTarget.wasmDecamelizedDefaultNameOrNull(): String? = if (platformType == KotlinPlatformType.wasm) {
    val defaultWasmTargetName = wasmTargetType?.let {
        KotlinWasmTargetPreset.WASM_PRESET_NAME + it.name.toLowerCaseAsciiOnly().capitalizeAsciiOnly()
    }

    defaultWasmTargetName
        ?.takeIf {
            targetName == defaultWasmTargetName
        }?.decamelize()
} else null
