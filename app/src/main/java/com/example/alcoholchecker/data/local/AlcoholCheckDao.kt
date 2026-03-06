package com.example.alcoholchecker.data.local

import androidx.room.*
import com.example.alcoholchecker.data.model.AlcoholCheckRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface AlcoholCheckDao {

    @Insert
    suspend fun insertRecord(record: AlcoholCheckRecord): Long

    @Query("SELECT * FROM alcohol_check_records ORDER BY checkedAt DESC")
    fun getAllRecords(): Flow<List<AlcoholCheckRecord>>

    @Query("SELECT * FROM alcohol_check_records WHERE userId = :userId ORDER BY checkedAt DESC")
    fun getRecordsByUser(userId: String): Flow<List<AlcoholCheckRecord>>

    @Query("SELECT * FROM alcohol_check_records WHERE checkedAt BETWEEN :startTime AND :endTime ORDER BY checkedAt DESC")
    fun getRecordsByDateRange(startTime: Long, endTime: Long): Flow<List<AlcoholCheckRecord>>

    @Query("SELECT * FROM alcohol_check_records WHERE result = '検出' ORDER BY checkedAt DESC")
    fun getDetectedRecords(): Flow<List<AlcoholCheckRecord>>
}
