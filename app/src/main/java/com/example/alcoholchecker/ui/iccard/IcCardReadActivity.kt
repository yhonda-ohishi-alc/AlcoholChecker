package com.example.alcoholchecker.ui.iccard

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.alcoholchecker.databinding.ActivityIcCardReadBinding
import com.example.alcoholchecker.nfc.CardType
import com.example.alcoholchecker.nfc.IcCardData
import com.example.alcoholchecker.nfc.NfcReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IcCardReadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIcCardReadBinding
    private var nfcAdapter: NfcAdapter? = null
    private val nfcReader = NfcReader()
    private var lastCardData: IcCardData? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIcCardReadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.title = "IC カード読み取り"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        if (nfcAdapter == null) {
            binding.tvStatus.text = "このデバイスはNFCに対応していません"
            binding.tvInstruction.visibility = View.GONE
            return
        }

        if (!nfcAdapter!!.isEnabled) {
            binding.tvStatus.text = "NFCが無効です。設定で有効にしてください"
            binding.tvInstruction.visibility = View.GONE
            return
        }

        binding.tvStatus.text = "待機中"
        binding.tvInstruction.text = "ICカードをデバイスにかざしてください"
    }

    override fun onResume() {
        super.onResume()
        enableNfcForegroundDispatch()
    }

    override fun onPause() {
        super.onPause()
        disableNfcForegroundDispatch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (NfcAdapter.ACTION_TECH_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TAG_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action
        ) {
            @Suppress("DEPRECATION")
            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return
            handleTag(tag)
        }
    }

    private fun enableNfcForegroundDispatch() {
        val adapter = nfcAdapter ?: return
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )
        adapter.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    private fun disableNfcForegroundDispatch() {
        nfcAdapter?.disableForegroundDispatch(this)
    }

    private fun handleTag(tag: Tag) {
        binding.tvStatus.text = "読み取り中..."
        binding.tvInstruction.text = "カードを離さないでください"
        binding.layoutResult.visibility = View.GONE

        lifecycleScope.launch {
            val cardData = withContext(Dispatchers.IO) {
                nfcReader.readCard(tag)
            }
            lastCardData = cardData
            displayResult(cardData)
        }
    }

    private fun displayResult(data: IcCardData) {
        binding.tvStatus.text = "読み取り完了"
        binding.tvInstruction.text = "別のカードを読み取るには再度かざしてください"
        binding.layoutResult.visibility = View.VISIBLE

        when (data.cardType) {
            CardType.DRIVER_LICENSE -> displayLicenseResult(data)
            CardType.CAR_INSPECTION -> displayCarInspectionResult(data)
            CardType.OTHER -> displayOtherResult(data)
        }
    }

    private fun displayLicenseResult(data: IcCardData) {
        binding.tvCardType.text = "運転免許証"
        binding.tvCardId.text = "カードID: ${data.cardId}"

        val details = buildString {
            if (data.issueDate != null) {
                appendLine("交付日: ${data.issueDate}")
            }
            if (data.expiryDate != null) {
                appendLine("有効期限: ${data.expiryDate}")
                appendLine()
                val status = checkExpiryStatus(data.expiryDate)
                appendLine("状態: $status")
            }
            if (data.remainCount != null) {
                appendLine("暗証番号残り回数: ${data.remainCount}")
            }
        }
        binding.tvDetails.text = details.ifEmpty { "詳細データの読み取りにはPIN認証が必要です" }
        binding.tvDetails.visibility = View.VISIBLE
    }

    private fun displayCarInspectionResult(data: IcCardData) {
        binding.tvCardType.text = "車検証 (自動車検査証)"
        binding.tvCardId.text = "カードID: ${data.cardId}"
        binding.tvDetails.text = "車検証ICカードを検出しました"
        binding.tvDetails.visibility = View.VISIBLE
    }

    private fun displayOtherResult(data: IcCardData) {
        binding.tvCardType.text = "ICカード"
        binding.tvCardId.text = "カードID: ${data.cardId}"

        val details = buildString {
            if (data.felicaIdm != null) {
                appendLine("FeliCa IDm: ${data.felicaIdm}")
            }
        }
        binding.tvDetails.text = details.ifEmpty { "一般ICカード" }
        binding.tvDetails.visibility = View.VISIBLE
    }

    private fun checkExpiryStatus(expiryDateStr: String): String {
        return try {
            val parts = expiryDateStr.split("/")
            val year = parts[0].toInt()
            val month = parts[1].toInt()
            val day = parts[2].toInt()

            val expiry = java.time.LocalDate.of(year, month, day)
            val today = java.time.LocalDate.now()
            val warningDate = today.plusDays(30)

            when {
                expiry.isBefore(today) -> "期限切れ"
                expiry.isBefore(warningDate) -> "期限間近 (残り${java.time.temporal.ChronoUnit.DAYS.between(today, expiry)}日)"
                else -> "有効"
            }
        } catch (e: Exception) {
            "不明"
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
