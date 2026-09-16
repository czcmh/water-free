package com.waterfree.minwater.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.waterfree.minwater.R
import com.waterfree.minwater.data.ConfigStore
import com.waterfree.minwater.databinding.ActivityLoginBinding
import com.waterfree.minwater.network.ApiError
import com.waterfree.minwater.network.WaterApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private val api = WaterApi()
    private var isLoggingIn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = ConfigStore.load(this)
        if (config.autoEnterWaterPage && config.token.isNotBlank() && config.account.isNotBlank() && config.password.isNotBlank()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.accountInput.setText(config.account)
        binding.passwordInput.setText(config.password)

        binding.loginButton.setOnClickListener {
            if (isLoggingIn) return@setOnClickListener
            lifecycleScope.launch {
                setLoginLoading(true)
                binding.statusText.text = getString(R.string.logging_in)
                try {
                    withContext(Dispatchers.IO) {
                        val latest = ConfigStore.load(this@LoginActivity)
                        latest.account = binding.accountInput.text.toString().trim()
                        latest.password = binding.passwordInput.text.toString().trim()
                        api.login(latest)
                        val recent = api.fetchRecentDevices(latest)
                        recent.forEach { device ->
                            val existing = latest.devices.find { it.code == device.code }
                            if (existing == null) {
                                latest.devices.add(device)
                            } else {
                                existing.position = device.position
                            }
                        }
                        ConfigStore.save(this@LoginActivity, latest)
                    }
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                } catch (exc: ApiError) {
                    binding.statusText.text = exc.message
                    Toast.makeText(this@LoginActivity, exc.message, Toast.LENGTH_SHORT).show()
                } catch (exc: Exception) {
                    binding.statusText.text = exc.message
                    Toast.makeText(this@LoginActivity, exc.message, Toast.LENGTH_SHORT).show()
                } finally {
                    setLoginLoading(false)
                }
            }
        }
    }

    private fun setLoginLoading(loading: Boolean) {
        isLoggingIn = loading
        binding.accountInput.isEnabled = !loading
        binding.passwordInput.isEnabled = !loading
        binding.loginButton.isEnabled = !loading
        binding.loginButton.text = getString(if (loading) R.string.logging_in else R.string.login)
    }
}
