package com.kuma.bluetoothdebugassistant

import com.kuma.bluetoothdebugassistant.project.MAX_PROJECT_NAME_LENGTH
import com.kuma.bluetoothdebugassistant.project.ProjectLayout
import com.kuma.bluetoothdebugassistant.project.ProjectSamples
import com.kuma.bluetoothdebugassistant.project.ProjectLimitException
import com.kuma.bluetoothdebugassistant.project.ProjectRules
import com.kuma.bluetoothdebugassistant.project.ProjectValidationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectRulesTest {
    @Test
    fun `name is trimmed and must be non-empty with at most 30 characters`() {
        assertEquals("控制面板", ProjectRules.normalizeName("  控制面板  "))
        assertFails<ProjectValidationException> { ProjectRules.normalizeName("   ") }
        assertEquals(
            "a".repeat(MAX_PROJECT_NAME_LENGTH),
            ProjectRules.normalizeName("a".repeat(MAX_PROJECT_NAME_LENGTH)),
        )
        assertFails<ProjectValidationException> {
            ProjectRules.normalizeName("a".repeat(MAX_PROJECT_NAME_LENGTH + 1))
        }
    }

    @Test
    fun `capacity rejects a sixth project`() {
        ProjectRules.requireCapacity(4)
        assertFails<ProjectLimitException> { ProjectRules.requireCapacity(5) }
        assertFails<ProjectLimitException> { ProjectRules.requireCapacity(6) }
    }

    @Test
    fun `duplicate names are unique and never exceed 30 characters`() {
        assertEquals(
            "控制面板 副本",
            ProjectRules.duplicateName("控制面板", listOf("控制面板")),
        )
        assertEquals(
            "控制面板 副本 3",
            ProjectRules.duplicateName(
                "控制面板",
                listOf("控制面板", "控制面板 副本", "控制面板 副本 2"),
            ),
        )
        val longCopy = ProjectRules.duplicateName(
            "a".repeat(30),
            listOf("a".repeat(30)),
        )
        assertTrue(longCopy.length <= MAX_PROJECT_NAME_LENGTH)
        assertTrue(longCopy.endsWith(" 副本"))
    }

    @Test
    fun `sample project has required stable data and default layout`() {
        val project = ProjectSamples.create(now = 1234L)

        assertEquals("default-project", project.id)
        assertEquals("默认项目", project.name)
        assertNull(project.boundDeviceName)
        assertNull(project.boundDeviceAddress)
        assertEquals(1234L, project.createdAt)
        assertEquals(1234L, project.updatedAt)
        val layout = Json.decodeFromString<ProjectLayout>(project.layoutJson)
        assertTrue(layout.items.isEmpty())
    }

    private inline fun <reified T : Throwable> assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            if (error !is T) throw error
        }
    }
}
