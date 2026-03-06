package com.example.alcoholchecker.ui.login

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.alcoholchecker.databinding.ActivityLoginBinding
import com.example.alcoholchecker.ui.home.HomeActivity

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnLogin.setOnClickListener {
            val userId = binding.etUserId.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()

            if (userId.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "ユーザーIDとパスワードを入力してください", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // TODO: 実際の認証処理を実装
            val intent = Intent(this, HomeActivity::class.java).apply {
                putExtra("USER_ID", userId)
            }
            startActivity(intent)
            finish()
        }
    }
}
