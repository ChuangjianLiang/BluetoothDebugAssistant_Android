package com.kuma.bluetoothdebugassistant.project

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query(
        """
        SELECT * FROM projects
        WHERE :query = ''
           OR name LIKE '%' || :query || '%' COLLATE NOCASE
           OR COALESCE(boundDeviceName, '') LIKE '%' || :query || '%' COLLATE NOCASE
           OR COALESCE(boundDeviceAddress, '') LIKE '%' || :query || '%' COLLATE NOCASE
        ORDER BY updatedAt DESC
        """,
    )
    fun observeProjects(query: String): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observeProject(id: String): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getProject(id: String): ProjectEntity?

    @Query("SELECT * FROM projects")
    suspend fun getProjects(): List<ProjectEntity>

    @Query("SELECT COUNT(*) FROM projects")
    suspend fun countProjects(): Int

    @Insert
    suspend fun insert(project: ProjectEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(project: ProjectEntity)

    @Update
    suspend fun update(project: ProjectEntity)

    @Delete
    suspend fun delete(project: ProjectEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM app_metadata WHERE `key` = :key)")
    suspend fun hasMetadata(key: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMetadata(metadata: AppMetadataEntity)

    @Transaction
    suspend fun initializeSampleOnce(project: ProjectEntity) {
        if (!hasMetadata(SAMPLE_CREATED_KEY)) {
            insertIfAbsent(project)
            putMetadata(AppMetadataEntity(SAMPLE_CREATED_KEY, "true"))
        }
    }
}

const val SAMPLE_CREATED_KEY = "sample_project_created"
