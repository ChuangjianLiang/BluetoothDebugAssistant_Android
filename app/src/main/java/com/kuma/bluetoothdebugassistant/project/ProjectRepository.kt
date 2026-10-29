package com.kuma.bluetoothdebugassistant.project

import kotlinx.coroutines.flow.Flow

interface ProjectRepository {
    fun observeProjects(query: String = ""): Flow<List<ProjectEntity>>
    fun observeProject(id: String): Flow<ProjectEntity?>
    suspend fun ensureSampleProject()
    suspend fun create(name: String): ProjectEntity
    suspend fun rename(id: String, name: String)
    suspend fun duplicate(id: String): ProjectEntity
    suspend fun updateLayout(id: String, layout: ProjectLayout)
    suspend fun bindDevice(id: String, deviceName: String?, deviceAddress: String?)
    suspend fun delete(id: String)
}

class ProjectValidationException(message: String) : IllegalArgumentException(message)

class ProjectLimitException : IllegalStateException("最多只能创建 $MAX_PROJECTS 个项目")

class ProjectNotFoundException : NoSuchElementException("项目不存在或已被删除")

object ProjectRules {
    fun normalizeName(value: String): String {
        val normalized = value.trim()
        if (normalized.isEmpty()) {
            throw ProjectValidationException("项目名称不能为空")
        }
        if (normalized.length > MAX_PROJECT_NAME_LENGTH) {
            throw ProjectValidationException("项目名称最多 $MAX_PROJECT_NAME_LENGTH 个字符")
        }
        return normalized
    }

    fun requireCapacity(currentCount: Int) {
        if (currentCount >= MAX_PROJECTS) throw ProjectLimitException()
    }

    fun duplicateName(sourceName: String, existingNames: Collection<String>): String {
        val normalizedSource = normalizeName(sourceName)
        val existing = existingNames.toSet()
        var sequence = 1
        while (true) {
            val suffix = if (sequence == 1) " 副本" else " 副本 $sequence"
            val baseLength = (MAX_PROJECT_NAME_LENGTH - suffix.length).coerceAtLeast(0)
            val candidate = normalizedSource.take(baseLength).trimEnd() + suffix
            if (candidate !in existing) return candidate
            sequence += 1
        }
    }
}

object ProjectSamples {
    const val ID = "default-project"
    const val NAME = "默认项目"
    const val EMPTY_LAYOUT_JSON = """{"version":1,"items":[]}"""

    fun create(now: Long): ProjectEntity = ProjectEntity(
        id = ID,
        name = NAME,
        boundDeviceName = null,
        boundDeviceAddress = null,
        layoutJson = EMPTY_LAYOUT_JSON,
        createdAt = now,
        updatedAt = now,
    )
}

const val MAX_PROJECTS = 5
const val MAX_PROJECT_NAME_LENGTH = 30
