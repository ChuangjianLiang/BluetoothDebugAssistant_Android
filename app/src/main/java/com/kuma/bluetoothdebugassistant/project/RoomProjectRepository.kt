package com.kuma.bluetoothdebugassistant.project

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RoomProjectRepository(
    private val dao: ProjectDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ProjectRepository {
    private val mutationMutex = Mutex()

    override fun observeProjects(query: String): Flow<List<ProjectEntity>> =
        dao.observeProjects(query.trim())

    override fun observeProject(id: String): Flow<ProjectEntity?> =
        dao.observeProject(id)

    override suspend fun ensureSampleProject() {
        mutationMutex.withLock {
            dao.initializeSampleOnce(ProjectSamples.create(now()))
        }
    }

    override suspend fun create(name: String): ProjectEntity = mutationMutex.withLock {
        val normalizedName = ProjectRules.normalizeName(name)
        ProjectRules.requireCapacity(dao.countProjects())
        val timestamp = now()
        val project = ProjectEntity(
            id = newId(),
            name = normalizedName,
            boundDeviceName = null,
            boundDeviceAddress = null,
            layoutJson = ProjectSamples.EMPTY_LAYOUT_JSON,
            createdAt = timestamp,
            updatedAt = timestamp,
        )
        dao.insert(project)
        project
    }

    override suspend fun rename(id: String, name: String) {
        mutationMutex.withLock {
            val normalizedName = ProjectRules.normalizeName(name)
            val project = dao.getProject(id) ?: throw ProjectNotFoundException()
            dao.update(project.copy(name = normalizedName, updatedAt = now()))
        }
    }

    override suspend fun duplicate(id: String): ProjectEntity = mutationMutex.withLock {
        ProjectRules.requireCapacity(dao.countProjects())
        val source = dao.getProject(id) ?: throw ProjectNotFoundException()
        val copyName = ProjectRules.duplicateName(
            sourceName = source.name,
            existingNames = dao.getProjects().map(ProjectEntity::name),
        )
        val timestamp = now()
        val copy = source.copy(
            id = newId(),
            name = copyName,
            createdAt = timestamp,
            updatedAt = timestamp,
        )
        dao.insert(copy)
        copy
    }

    override suspend fun updateLayout(id: String, layout: ProjectLayout) {
        mutationMutex.withLock {
            val project = dao.getProject(id) ?: throw ProjectNotFoundException()
            dao.update(
                project.copy(
                    layoutJson = ProjectLayoutJson.encode(layout.normalized()),
                    updatedAt = now(),
                ),
            )
        }
    }

    override suspend fun bindDevice(
        id: String,
        deviceName: String?,
        deviceAddress: String?,
    ) {
        mutationMutex.withLock {
            val project = dao.getProject(id) ?: throw ProjectNotFoundException()
            dao.update(
                project.copy(
                    boundDeviceName = deviceName,
                    boundDeviceAddress = deviceAddress,
                    updatedAt = now(),
                ),
            )
        }
    }

    override suspend fun delete(id: String) {
        mutationMutex.withLock {
            val project = dao.getProject(id) ?: throw ProjectNotFoundException()
            dao.delete(project)
        }
    }
}
