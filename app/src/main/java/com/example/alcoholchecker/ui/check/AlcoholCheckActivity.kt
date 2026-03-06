package com.example.alcoholchecker.ui.check

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.alcoholchecker.data.local.AppDatabase
import com.example.alcoholchecker.data.model.AlcoholCheckRecord
import com.example.alcoholchecker.databinding.ActivityAlcoholCheckBinding
import kotlinx.coroutines.launch

class AlcoholCheckActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlcoholCheckBinding
    private lateinit var database: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlcoholCheckBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getDatabase(this)

        val userId = intent.getStringExtra("USER_ID") ?: ""
        val checkType = intent.getStringExtra("CHECK_TYPE") ?: ""

        binding.tvCheckType.text = checkType

        binding.btnSubmit.setOnClickListener {
            val levelText = binding.etAlcoholLevel.text.toString().trim()
            if (levelText.isEmpty()) {
                Toast.makeText(this, "アルコール濃度を入力してください", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val level = levelText.toFloatOrNull() ?: 0f
            val result = if (level > 0.0f) "検出" else "正常"
            val note = binding.etNote.text.toString().trim()

            val record = AlcoholCheckRecord(
                userId = userId,
                userName = "",  // TODO: ユーザー名を取得
                checkType = checkType,
                alcoholLevel = level,
                result = result,
                photoPath = null,   // TODO: カメラ撮影実装
                latitude = null,    // TODO: 位置情報取得
                longitude = null,
                note = note.ifEmpty { null }
            )

            lifecycleScope.launch {
                database.alcoholCheckDao().insertRecord(record)
                runOnUiThread {
                    Toast.makeText(
                        this@AlcoholCheckActivity,
                        "記録しました: $result",
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            }
        }
    }
}
