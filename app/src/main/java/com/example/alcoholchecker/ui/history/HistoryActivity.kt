package com.example.alcoholchecker.ui.history

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.alcoholchecker.data.local.AppDatabase
import com.example.alcoholchecker.data.model.AlcoholCheckRecord
import com.example.alcoholchecker.databinding.ActivityHistoryBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var database: AppDatabase
    private val records = mutableListOf<AlcoholCheckRecord>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getDatabase(this)
        val userId = intent.getStringExtra("USER_ID") ?: ""

        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        // TODO: RecyclerView Adapterを実装

        lifecycleScope.launch {
            database.alcoholCheckDao().getRecordsByUser(userId).collectLatest { list ->
                records.clear()
                records.addAll(list)
                // TODO: Adapterに通知
            }
        }
    }
}
