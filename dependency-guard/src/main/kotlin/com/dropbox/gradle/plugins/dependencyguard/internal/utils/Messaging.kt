package com.dropbox.gradle.plugins.dependencyguard.internal.utils

import com.dropbox.gradle.plugins.dependencyguard.DependencyGuardPlugin.Companion.DEPENDENCY_GUARD_BASELINE_TASK_NAME
import com.dropbox.gradle.plugins.dependencyguard.internal.getQualifiedBaselineTaskForProjectPath

internal object Messaging {
    const val dependencyChangeDetected = "***** DEPENDENCY CHANGE DETECTED *****"

    fun rebaselineMessage(projectPath: String): String = """
        If this is intentional, re-baseline using ./gradlew ${getQualifiedBaselineTaskForProjectPath(projectPath)}
        Or use ./gradlew $DEPENDENCY_GUARD_BASELINE_TASK_NAME to re-baseline dependencies in entire project.
    """.trimIndent()
}