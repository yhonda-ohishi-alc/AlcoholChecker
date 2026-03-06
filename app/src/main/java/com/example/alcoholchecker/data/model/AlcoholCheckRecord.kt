package com.example.alcoholchecker.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "alcohol_check_records")
data class AlcoholCheckRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val userId: String,
    val userName: String,
    val checkType: String,          // "出勤前" or "退勤後"
    val alcoholLevel: Float,        // mg/L
    val result: String,             // "正常" or "検出"
    val photoPath: String?,         // 顔写真パス
    val latitude: Double?,
    val longitude: Double?,
    val note: String?,
    val checkedAt: Long = System.currentTimeMillis()
)
