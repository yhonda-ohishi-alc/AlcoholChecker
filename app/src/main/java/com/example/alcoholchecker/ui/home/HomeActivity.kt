package com.example.alcoholchecker.ui.home

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.alcoholchecker.databinding.ActivityHomeBinding
import com.example.alcoholchecker.ui.check.AlcoholCheckActivity
import com.example.alcoholchecker.ui.history.HistoryActivity

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val userId = intent.getStringExtra("USER_ID") ?: ""

        binding.btnCheckBefore.setOnClickListener {
            startCheck(userId, "出勤前")
        }

        binding.btnCheckAfter.setOnClickListener {
            startCheck(userId, "退勤後")
        }

        binding.btnHistory.setOnClickListener {
            val intent = Intent(this, HistoryActivity::class.java).apply {
                putExtra("USER_ID", userId)
            }
            startActivity(intent)
        }
    }

    private fun startCheck(userId: String, checkType: String) {
        val intent = Intent(this, AlcoholCheckActivity::class.java).apply {
            putExtra("USER_ID", userId)
            putExtra("CHECK_TYPE", checkType)
        }
        startActivity(intent)
    }
}
