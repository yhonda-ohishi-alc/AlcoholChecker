package com.example.alcoholchecker.data.repository

import com.example.alcoholchecker.data.local.AlcoholCheckDao
import com.example.alcoholchecker.data.model.AlcoholCheckRecord
import kotlinx.coroutines.flow.Flow

class AlcoholCheckRepository(private val dao: AlcoholCheckDao) {

    val allRecords: Flow<List<AlcoholCheckRecord>> = dao.getAllRecords()

    val detectedRecords: Flow<List<AlcoholCheckRecord>> = dao.getDetectedRecords()

    suspend fun insertRecord(record: AlcoholCheckRecord): Long {
        return dao.insertRecord(record)
    }

    fun getRecordsByUser(userId: String): Flow<List<AlcoholCheckRecord>> {
        return dao.getRecordsByUser(userId)
    }

    fun getRecordsByDateRange(startTime: Long, endTime: Long): Flow<List<AlcoholCheckRecord>> {
        return dao.getRecordsByDateRange(startTime, endTime)
    }
}
