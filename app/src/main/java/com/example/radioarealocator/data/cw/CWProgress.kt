package com.example.radioarealocator.data.cw

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cw_progress")
data class CWProgress(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val courseId: Int,
    val lessonId: Int,
    val completedAt: Long,
    val accuracy: Float,
    val wpm: Int,
    val duration: Int
)
