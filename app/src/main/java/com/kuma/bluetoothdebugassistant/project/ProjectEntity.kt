package com.kuma.bluetoothdebugassistant.project

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val boundDeviceName: String?,
    val boundDeviceAddress: String?,
    val layoutJson: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "app_metadata")
data class AppMetadataEntity(
    @PrimaryKey val key: String,
    val value: String,
)
