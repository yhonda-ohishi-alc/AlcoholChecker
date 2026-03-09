package com.example.alcoholchecker.admin

import android.app.Activity
import android.os.Bundle
import android.util.Log

class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("DeviceAdmin", "ADMIN_POLICY_COMPLIANCE called")
        setResult(RESULT_OK)
        finish()
    }
}
