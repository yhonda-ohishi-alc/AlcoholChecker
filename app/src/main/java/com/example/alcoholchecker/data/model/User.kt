package com.example.alcoholchecker.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class User(
    @PrimaryKey
    val userId: String,
    val name: String,
    val department: String,
    val role: String,       // "driver" or "admin"
    val createdAt: Long = System.currentTimeMillis()
)
