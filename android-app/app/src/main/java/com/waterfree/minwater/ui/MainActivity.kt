package com.waterfree.minwater.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.waterfree.minwater.R
import com.waterfree.minwater.data.AppConfig
import com.waterfree.minwater.data.ConfigStore
import com.waterfree.minwater.data.DeviceItem
import com.waterfree.minwater.databinding.ActivityMainBinding
import com.waterfree.minwater.network.ApiError
import com.waterfree.minwater.network.WaterApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var config: AppConfig
    private lateinit var adapter: DeviceAdapter
    private val api = WaterApi()
    private var isImportingRecent = false
    private var isRefreshingBalance = false
    private var hasSyncedDeviceStates = false
    private val deviceAutoRefreshJobs = mutableMapOf<String, Job>()

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            launchScanner()
        } else {
            showToast("相机权限被拒绝")
        }
    }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        runAction("正在添加扫描设备") { addScannedDevice(contents) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        config = ConfigStore.load(this)
        if (config.token.isBlank()) {
            navigateToLogin()
            return
        }
        setupList()
        bindActions()
        refreshHeader()
    }

    override fun onResume() {
        super.onResume()
        config = ConfigStore.load(this)
        if (config.token.isBlank()) {
            navigateToLogin()
            return
        }
        refreshHeader()
        adapter.replaceAll(config.devices)
        loadBalance()
        if (!hasSyncedDeviceStates) {
            hasSyncedDeviceStates = true
            runAction("正在同步设备状态") { refreshAllDeviceStates() }
        } else {
            scheduleAutoRefreshForActiveDevices()
        }
    }

    override fun onStop() {
        super.onStop()
        cancelAutoRefreshJobs()
    }

    private fun setupList() {
        adapter = DeviceAdapter(
            items = mutableListOf(),
            onRefresh = { item -> runAction("正在刷新 ${item.code}") { refreshDevice(item) } },
            onOpen = openAction@{ item ->
                if (item.isOpening) {
                    return@openAction
                }
                runAction(
                    status = "正在开水 ${item.code}",
                    before = { setDeviceOpening(item, true) },
                    after = { setDeviceOpening(item, false) },
                ) { openWater(item) }
            },
            onDelete = { item, position -> deleteDeviceWithUndo(item, position) },
        )
        binding.deviceList.layoutManager = LinearLayoutManager(this)
        binding.deviceList.adapter = adapter
        adapter.replaceAll(config.devices)

        val swipeCallback = SwipeToDeleteCallback(adapter)
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.deviceList)
    }

    private fun bindActions() {
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, ConfigActivity::class.java))
        }
        binding.refreshRecentIcon.setOnClickListener {
            if (isImportingRecent) return@setOnClickListener
            runAction(
                status = getString(R.string.importing_recent),
                before = { setImportRecentLoading(true) },
                after = { setImportRecentLoading(false) },
            ) { importRecentDevices() }
        }
        binding.scanIconButton.setOnClickListener {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
        binding.addManualButton.setOnClickListener {
            runAction("正在添加设备") { addManualDevice() }
        }
        binding.summaryCard.setOnClickListener {
            if (isRefreshingBalance) return@setOnClickListener
            loadBalance(manual = true)
        }
    }

    private fun refreshHeader() {
        val effectiveCustomerId = config.effectiveCustomerId().ifBlank { "（未设置）" }
        runOnUiThread { binding.customerIdText.text = effectiveCustomerId }
    }

    private fun loadBalance(manual: Boolean = false) {
        if (isRefreshingBalance) return
        lifecycleScope.launch {
            setBalanceLoading(true)
            if (manual) {
                showStatus(getString(R.string.refreshing_balance))
            }
            try {
                val balance = withContext(Dispatchers.IO) { api.fetchBalance(config) }
                binding.balanceText.text = if (balance.amyBalance.isNotBlank()) "¥${balance.amyBalance}" else "--"
                binding.waterBeanText.text = balance.waterBean.ifBlank { "--" }
                if (manual) {
                    showStatus(getString(R.string.balance_refreshed))
                }
            } catch (e: Exception) {
                binding.balanceText.text = "--"
                binding.waterBeanText.text = "--"
                showStatus("余额查询失败: ${e.message.orEmpty()}")
            } finally {
                persist()
                setBalanceLoading(false)
            }
        }
    }

    private fun addManualDevice() {
        val code = binding.deviceIdInput.text.toString().trim()
        if (code.isBlank()) throw ApiError("设备编号不能为空")
        val info = api.fetchDeviceInfo(config, code)
        upsertDevice(info)
        runOnUiThread { binding.deviceIdInput.text?.clear() }
        showStatus("已保存 ${info.code}")
    }

    private fun addScannedDevice(rawValue: String) {
        val info = api.fetchDeviceInfo(config, rawValue)
        upsertDevice(info)
        showStatus("已扫描 ${info.code}")
    }

    private fun refreshDevice(item: DeviceItem) {
        val info = api.fetchDeviceInfo(config, item.code)
        item.code = info.code
        item.position = info.position
        item.statusText = info.statusText
        persist()
        runOnUiThread { adapter.replaceAll(config.devices) }
        scheduleAutoRefreshIfNeeded(item.code, item.statusText)
        showStatus("已刷新 ${item.code}")
    }

    private fun importRecentDevices() {
        val devices = api.fetchRecentDevices(config)
        devices.forEach { device ->
            val refreshed = runCatching { api.fetchDeviceInfo(config, device.code) }.getOrElse { device }
            upsertDevice(refreshed, refreshUi = false)
        }
        persist()
        runOnUiThread {
            adapter.replaceAll(config.devices)
            refreshHeader()
        }
        scheduleAutoRefreshForActiveDevices()
        showStatus("已导入最近设备")
    }

    private fun refreshAllDeviceStates() {
        if (config.devices.isEmpty()) {
            showStatus("暂无设备")
            return
        }
        val refreshedDevices = config.devices.map { item ->
            runCatching { api.fetchDeviceInfo(config, item.code) }.getOrElse { item.copy() }
        }
        config.devices.clear()
        config.devices.addAll(refreshedDevices)
        persist()
        runOnUiThread { adapter.replaceAll(config.devices) }
        scheduleAutoRefreshForActiveDevices()
        showStatus("设备状态已同步")
    }

    private fun openWater(item: DeviceItem) {
        val latest = api.fetchDeviceInfo(config, item.code)
        item.position = latest.position
        item.statusText = latest.statusText
        persist()
        runOnUiThread { adapter.replaceAll(config.devices) }
        val message = api.openWater(config, item.code)
        item.statusText = "当前用户"
        persist()
        runOnUiThread { adapter.replaceAll(config.devices) }
        refreshHeader()
        scheduleAutoRefreshIfNeeded(item.code, item.statusText)
        showStatus("${item.code} 开水成功，正在自动刷新状态")
        showToast("${item.code}: $message")
        runOnUiThread { loadBalance() }
    }

    private fun deleteDeviceWithUndo(item: DeviceItem, adapterPosition: Int) {
        val originalIndex = config.devices.indexOfFirst { it.code == item.code }
        if (originalIndex < 0) return
        val removed = config.devices.removeAt(originalIndex)
        deviceAutoRefreshJobs.remove(removed.code)?.cancel()
        persist()
        showStatus(getString(R.string.device_deleted, removed.code))

        Snackbar.make(binding.root, getString(R.string.device_deleted, removed.code), Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) {
                val insertAt = originalIndex.coerceAtMost(config.devices.size)
                config.devices.add(insertAt, removed)
                persist()
                adapter.replaceAll(config.devices)
                scheduleAutoRefreshIfNeeded(removed.code, removed.statusText)
                showStatus(getString(R.string.device_restored, removed.code))
            }
            .show()
    }

    private fun upsertDevice(info: DeviceItem, refreshUi: Boolean = true) {
        val existing = config.devices.find { it.code == info.code }
        if (existing == null) {
            config.devices.add(0, info)
        } else {
            if (info.position.isNotBlank()) existing.position = info.position
            if (info.statusText.isNotBlank()) existing.statusText = info.statusText
        }
        persist()
        if (refreshUi) {
            runOnUiThread {
                adapter.replaceAll(config.devices)
                refreshHeader()
            }
        }
    }

    private fun persist() {
        ConfigStore.save(this, config)
    }

    private fun launchScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("扫描设备二维码")
            setBeepEnabled(true)
            setOrientationLocked(true)
            addExtra(Intents.Scan.CAMERA_ID, 0)
        }
        scanLauncher.launch(options)
    }

    private fun runAction(
        status: String,
        before: (() -> Unit)? = null,
        after: (() -> Unit)? = null,
        action: suspend () -> Unit,
    ) {
        lifecycleScope.launch {
            before?.invoke()
            showStatus(status)
            try {
                withContext(Dispatchers.IO) { action() }
            } catch (exc: ApiError) {
                showStatus(exc.message.orEmpty())
                showToast(exc.message.orEmpty())
            } catch (exc: Exception) {
                showStatus(exc.message.orEmpty())
                showToast(exc.message.orEmpty())
            } finally {
                persist()
                after?.invoke()
            }
        }
    }

    private fun setImportRecentLoading(loading: Boolean) {
        isImportingRecent = loading
        binding.refreshRecentIcon.isEnabled = !loading
        binding.refreshRecentIcon.alpha = if (loading) 0.4f else 1f
    }

    private fun setBalanceLoading(loading: Boolean) {
        isRefreshingBalance = loading
        binding.summaryCard.isEnabled = !loading
        binding.summaryCard.alpha = if (loading) 0.82f else 1f
    }

    private fun setDeviceOpening(item: DeviceItem, loading: Boolean) {
        item.isOpening = loading
        runOnUiThread { adapter.replaceAll(config.devices) }
    }

    private fun scheduleAutoRefreshForActiveDevices() {
        config.devices
            .filter { it.statusText == "当前用户" || it.statusText == "使用中" }
            .forEach { item -> scheduleAutoRefreshIfNeeded(item.code, item.statusText) }
    }

    private fun scheduleAutoRefreshIfNeeded(deviceCode: String, statusText: String) {
        if (statusText != "当前用户" && statusText != "使用中") {
            deviceAutoRefreshJobs.remove(deviceCode)?.cancel()
            return
        }
        deviceAutoRefreshJobs.remove(deviceCode)?.cancel()
        val job = lifecycleScope.launch {
            repeat(24) {
                delay(5000)
                val target = config.devices.find { it.code == deviceCode } ?: return@launch
                val latest = runCatching {
                    withContext(Dispatchers.IO) { api.fetchDeviceInfo(config, deviceCode) }
                }.getOrElse {
                    return@repeat
                }
                target.position = latest.position
                target.statusText = latest.statusText
                persist()
                adapter.replaceAll(config.devices)
                if (latest.statusText == "空闲") {
                    showStatus("$deviceCode 已恢复空闲")
                    return@launch
                }
            }
        }
        deviceAutoRefreshJobs[deviceCode] = job
        job.invokeOnCompletion {
            deviceAutoRefreshJobs.remove(deviceCode, job)
        }
    }

    private fun cancelAutoRefreshJobs() {
        deviceAutoRefreshJobs.values.forEach { it.cancel() }
        deviceAutoRefreshJobs.clear()
    }

    private fun navigateToLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun showStatus(text: String) {
        runOnUiThread { binding.statusText.text = text }
    }

    private fun showToast(text: String) {
        runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    }
}
