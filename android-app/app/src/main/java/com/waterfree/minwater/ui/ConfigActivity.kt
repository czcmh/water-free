package com.waterfree.minwater.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.waterfree.minwater.data.ConfigStore
import com.waterfree.minwater.databinding.ActivityConfigBinding

class ConfigActivity : AppCompatActivity() {
    private lateinit var binding: ActivityConfigBinding
    private var devTapCount = 0
    private var lastTapTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val config = ConfigStore.load(this)
        binding.accountInput.setText(config.account)
        binding.passwordInput.setText(config.password)
        binding.customerIdOverrideInput.setText(config.customerIdOverride)
        binding.loginCustomerIdText.text = "登录客户ID：${config.loginCustomerId.ifBlank { "（空）" }}"
        binding.autoEnterSwitch.isChecked = config.autoEnterWaterPage

        // 默认隐藏自定义客户ID
        val hasOverride = config.customerIdOverride.isNotBlank()
        binding.advancedContainer.visibility = if (hasOverride) View.VISIBLE else View.GONE

        // 连续点击登录客户ID文字 5 次解锁高级设置
        binding.loginCustomerIdText.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTapTime > 1500) {
                devTapCount = 0
            }
            lastTapTime = now
            devTapCount++

            if (devTapCount >= 5 && binding.advancedContainer.visibility != View.VISIBLE) {
                binding.advancedContainer.visibility = View.VISIBLE
                Toast.makeText(this, "已解锁高级设置", Toast.LENGTH_SHORT).show()
                devTapCount = 0
            } else if (devTapCount >= 3 && binding.advancedContainer.visibility != View.VISIBLE) {
                val remaining = 5 - devTapCount
                Toast.makeText(this, "再点击 $remaining 次解锁高级设置", Toast.LENGTH_SHORT).show()
            }
        }

        binding.saveButton.setOnClickListener {
            val latest = ConfigStore.load(this)
            val newAccount = binding.accountInput.text.toString().trim()
            val newPassword = binding.passwordInput.text.toString().trim()
            val credentialsChanged = latest.account != newAccount || latest.password != newPassword
            latest.account = newAccount
            latest.password = newPassword
            latest.customerIdOverride = binding.customerIdOverrideInput.text.toString().trim()
            latest.autoEnterWaterPage = binding.autoEnterSwitch.isChecked
            if (credentialsChanged) {
                latest.token = ""
                latest.loginCustomerId = ""
                latest.areaId = ""
                latest.devices.clear()
            }
            ConfigStore.save(this, latest)
            val message = if (credentialsChanged) "账号已更新，请重新登录" else "设置已保存"
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.clearTokenButton.setOnClickListener {
            val latest = ConfigStore.load(this)
            latest.token = ""
            ConfigStore.save(this, latest)
            Toast.makeText(this, "令牌已清除", Toast.LENGTH_SHORT).show()
        }

        binding.logoutButton.setOnClickListener {
            val latest = ConfigStore.load(this)
            latest.token = ""
            ConfigStore.save(this, latest)
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }
}
