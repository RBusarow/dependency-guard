package com.dropbox.gradle.plugins.dependencyguard.internal.list

import com.dropbox.gradle.plugins.dependencyguard.DependencyGuardConfiguration
import com.dropbox.gradle.plugins.dependencyguard.DependencyGuardPlugin
import com.dropbox.gradle.plugins.dependencyguard.DependencyGuardPluginExtension
import com.dropbox.gradle.plugins.dependencyguard.internal.ConfigurationValidators
import com.dropbox.gradle.plugins.dependencyguard.internal.ConfigurationValidators.requirePluginConfig
import com.dropbox.gradle.plugins.dependencyguard.internal.DependencyGuardListReportWriter
import com.dropbox.gradle.plugins.dependencyguard.internal.DependencyGuardReportData
import com.dropbox.gradle.plugins.dependencyguard.internal.DependencyGuardReportType
import com.dropbox.gradle.plugins.dependencyguard.internal.DependencyVisitor
import com.dropbox.gradle.plugins.dependencyguard.internal.isRootProject
import com.dropbox.gradle.plugins.dependencyguard.internal.utils.DependencyListDiffResult
import com.dropbox.gradle.plugins.dependencyguard.internal.utils.OutputFileUtils
import com.dropbox.gradle.plugins.dependencyguard.internal.utils.Tasks.declareCompatibilities
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.initialization.dsl.ScriptHandler
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.TaskAction

public abstract class DependencyGuardListTask : DefaultTask() {

    init {
        group = DependencyGuardPlugin.DEPENDENCY_GUARD_TASK_GROUP
    }

    private fun generateReportForConfiguration(
        dependencyGuardConfiguration: DependencyGuardConfiguration
    ): DependencyGuardReportData {
        val configurationName = dependencyGuardConfiguration.configurationName

        var config = project.configurations.firstOrNull { it.name == configurationName }

        if (config == null) {
            if (forRootProject.get()) {
                // Assuming this is the root project
                config = project.buildscript.configurations.findByName(ScriptHandler.CLASSPATH_CONFIGURATION)
            }
            if (config == null) {
                throw GradleException("Could not find $configurationName in ${project.path}")
            }
        }

        val dependencies = DependencyVisitor.traverseDependenciesForConfiguration(config)

        return DependencyGuardReportData(
            projectPath = project.path,
            configurationName = configurationName,
            allowedFilter = dependencyGuardConfiguration.allowedFilter,
            baselineMap = dependencyGuardConfiguration.baselineMap,
            dependencies = dependencies,
        )
    }

    @get:Input
    public abstract val shouldBaseline: Property<Boolean>

    @get:Input
    public abstract val forRootProject: Property<Boolean>

    @get:Input
    public abstract val availableConfigurations: ListProperty<String>

    @get:Nested
    public abstract val monitoredConfigurations: ListProperty<DependencyGuardConfiguration>

    @Suppress("NestedBlockDepth")
    @TaskAction
    internal fun execute() {
        requirePluginConfig(
            isForRootProject = forRootProject.get(),
            availableConfigurations = availableConfigurations.get(),
            monitoredConfigurations = monitoredConfigurations.get()
        )

        val dependencyGuardConfigurations = monitoredConfigurations.get()
        ConfigurationValidators.validateConfigurationsAreAvailable(
            target = project,
            configurationNames = dependencyGuardConfigurations.map { it.configurationName }
        )

        val reports = mutableListOf<DependencyGuardReportData>()
        dependencyGuardConfigurations.forEach { dependencyGuardConfig ->
            val report: DependencyGuardReportData = generateReportForConfiguration(dependencyGuardConfig)
            reports.add(report)
        }

        // Throw Error if any Disallowed Dependencies are Found
        val reportsWithDisallowedDependencies = reports.filter { it.disallowed.isNotEmpty() }
        if (reportsWithDisallowedDependencies.isNotEmpty()) {
            throwErrorAboutDisallowedDependencies(reportsWithDisallowedDependencies)
        }

        val exceptionMessage = StringBuilder()
        dependencyGuardConfigurations.forEach { dependencyGuardConfig ->
            val report = reports.firstOrNull { it.configurationName == dependencyGuardConfig.configurationName }
            report?.let {
                val diffResult: DependencyListDiffResult = writeListReport(dependencyGuardConfig, report)
                when (diffResult) {
                    is DependencyListDiffResult.DiffPerformed.HasDiff -> {
                        // Print to console in color
                        println(diffResult.createDiffMessage(withColor = true))

                        // Add to exception message without color
                        exceptionMessage.appendLine(diffResult.createDiffMessage(withColor = false))
                    }
                    is DependencyListDiffResult.DiffPerformed.NoDiff -> {
                        // Print no diff message
                        println(diffResult.noDiffMessage)
                    }
                    is DependencyListDiffResult.BaselineCreated -> {
                        // TODO Move messaging here.  Currently it is printed elsewhere.
                    }
                }

                // If there was an exception message, throw an Exception with the message
                if (exceptionMessage.toString().isNotEmpty()) {
                    throw GradleException(exceptionMessage.toString())
                }
            }
        }
    }

    /**
     * @return Whether changes were detected
     */
    private fun writeListReport(
        dependencyGuardConfig: DependencyGuardConfiguration,
        report: DependencyGuardReportData
    ): DependencyListDiffResult {
        val reportType = DependencyGuardReportType.LIST
        val reportWriter = DependencyGuardListReportWriter(
            artifacts = dependencyGuardConfig.artifacts,
            modules = dependencyGuardConfig.modules
        )
        return reportWriter.writeReport(
            buildDirOutputFile = OutputFileUtils.buildDirOutputFile(
                project = project,
                configurationName = report.configurationName,
                reportType = reportType,
            ),
            projectDirOutputFile = OutputFileUtils.projectDirOutputFile(
                project = project,
                configurationName = report.configurationName,
                reportType = reportType,
            ),
            report = report,
            shouldBaseline = shouldBaseline.get()
        )
    }

    private fun throwErrorAboutDisallowedDependencies(reportsWithDisallowedDependencies: List<DependencyGuardReportData>) {
        val errorMessage = StringBuilder().apply {
            reportsWithDisallowedDependencies.forEach { report ->
                val disallowed = report.disallowed
                appendLine(
                    """Disallowed Dependencies found in ${project.path} for the configuration "${report.configurationName}" """
                )
                disallowed.forEach {
                    appendLine("\"${it.name}\",")
                }
                appendLine()
            }
            appendLine("These dependencies are included and must be removed based on the configured 'allowedFilter'.")
            appendLine()
        }.toString()

        throw GradleException(errorMessage)
    }

    internal fun setParams(
        project: Project,
        extension: DependencyGuardPluginExtension,
        shouldBaseline: Boolean
    ) {
        this.forRootProject.set(project.isRootProject())
        this.availableConfigurations.set(project.configurations.map { it.name })
        this.monitoredConfigurations.set(extension.configurations)
        this.shouldBaseline.set(shouldBaseline)

        declareCompatibilities()
    }
}
