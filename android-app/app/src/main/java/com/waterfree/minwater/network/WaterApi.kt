package com.waterfree.minwater.network

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.waterfree.minwater.data.AppConfig
import com.waterfree.minwater.data.DeviceItem
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class ApiError(message: String) : RuntimeException(message)

class WaterApi {
    private val client = OkHttpClient()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun login(config: AppConfig) {
        if (config.account.isBlank() || config.password.isBlank()) {
            throw ApiError("账号或密码为空")
        }
        val body = JsonObject().apply {
            addProperty("loginAccount", config.account)
            addProperty("password", config.password)
        }
        val response = requestJson(
            url = "https://dcxy-customer-app.dcrym.com/app/customer/login",
            method = "POST",
            config = config,
            includeToken = false,
            bodyJson = body.toString(),
        )
        if (response.code != 1000) {
            throw ApiError(response.message.ifBlank { "登录失败" })
        }
        val data = response.dataObject ?: throw ApiError("登录响应缺少数据")
        val token = data.get("token")?.asString.orEmpty()
        if (token.isBlank()) {
            throw ApiError("登录响应缺少令牌")
        }
        config.token = token
        config.loginCustomerId = data.get("customerId")?.asString.orEmpty()
        config.areaId = data.get("areaId")?.asString.orEmpty()
    }

    fun fetchRecentDevices(config: AppConfig): List<DeviceItem> {
        if (config.loginCustomerId.isBlank() || config.areaId.isBlank()) {
            throw ApiError("登录信息不完整")
        }
        val response = requestWithRelogin(
            url = "https://gx-app-server.dcrym.com/dcxy/api/gx/devices/lastUsedByCurrentUser?customerId=${config.loginCustomerId}&campusId=${config.areaId}",
            method = "GET",
            config = config,
        )
        if (response.code != 1000) {
            throw ApiError(response.message.ifBlank { "查询最近设备失败" })
        }
        val array = response.dataArray ?: return emptyList()
        return array.take(3).mapNotNull { item ->
            item.asJsonObjectOrNull()?.let { obj ->
                DeviceItem(
                    code = obj.get("code")?.asString.orEmpty(),
                    position = obj.get("position")?.asString.orEmpty(),
                    statusText = "",
                )
            }?.takeIf { it.code.isNotBlank() }
        }
    }

    fun fetchDeviceInfo(config: AppConfig, rawValue: String): DeviceItem {
        val normalized = normalizeScanValue(rawValue)
        val response = requestWithRelogin(
            url = "https://gx-app-server.dcrym.com/dcxy/api/gx/devices/$normalized",
            method = "GET",
            config = config,
        )
        if (response.code != 1000) {
            throw ApiError(response.message.ifBlank { "查询设备失败" })
        }
        val data = response.dataObject ?: throw ApiError("设备查询缺少数据")
        val isOnline = data.get("isOnline")?.asInt ?: 0
        val isUsed = data.get("isUsed")?.asInt ?: 0
        val isCurrentUserUsed = data.get("isCurrentUserUsed")?.asInt ?: 0
        return DeviceItem(
            code = data.get("code")?.asString.orEmpty(),
            position = data.get("position")?.asString.orEmpty(),
            statusText = mapStatusText(isOnline, isUsed, isCurrentUserUsed),
        ).also {
            if (it.code.isBlank()) throw ApiError("设备编号为空")
        }
    }

    fun openWater(config: AppConfig, deviceCode: String): String {
        val customerId = config.effectiveCustomerId()
        if (customerId.isBlank()) {
            throw ApiError("客户ID为空")
        }
        val status = fetchDeviceInfo(config, deviceCode)
        when (status.statusText) {
            "离线" -> throw ApiError("设备离线")
            "使用中" -> throw ApiError("设备使用中")
            "当前用户" -> throw ApiError("设备已在当前账号下使用中")
        }
        val body = JsonObject().apply {
            addProperty("customerId", customerId)
        }
        val response = requestWithRelogin(
            url = "https://gx-app-server.dcrym.com/dcxy/api/gx/devices/$deviceCode/beginning",
            method = "POST",
            config = config,
            bodyJson = body.toString(),
        )
        if (response.code != 1000) {
            throw ApiError(response.message.ifBlank { "开水失败" })
        }
        return response.message.ifBlank { "成功" }
    }

    /**
     * 查询账户余额（艾米余额 + 饮水豆）
     * GET https://dcxy-customer-app.dcrym.com/account/current/all
     */
    fun fetchBalance(config: AppConfig): BalanceInfo {
        val response = requestWithRelogin(
            url = "https://dcxy-customer-app.dcrym.com/account/current/all",
            method = "GET",
            config = config,
        )
        if (response.code != 1000) {
            throw ApiError(response.message.ifBlank { "查询余额失败" })
        }
        // data 可能是数组，也可能是对象里包含数组
        var amyBalance = ""
        var waterBean = ""

        val accounts = response.dataArray
            ?: response.dataObject?.let { obj ->
                // 尝试从 data 对象中找到数组字段
                obj.entrySet().firstOrNull { it.value?.isJsonArray == true }?.value?.asJsonArray
            }

        if (accounts != null) {
            for (i in 0 until accounts.size()) {
                val obj = accounts[i].asJsonObjectOrNull() ?: continue
                val serviceId = obj.get("serviceId")?.asInt ?: continue
                val money = obj.get("money")?.asString.orEmpty()
                when (serviceId) {
                    0 -> amyBalance = money
                    3 -> waterBean = money
                }
            }
        }
        return BalanceInfo(amyBalance = amyBalance, waterBean = waterBean)
    }

    private fun mapStatusText(isOnline: Int, isUsed: Int, isCurrentUserUsed: Int): String {
        return when {
            isOnline != 1 -> "离线"
            isUsed == 1 && isCurrentUserUsed == 1 -> "当前用户"
            isUsed == 1 -> "使用中"
            else -> "空闲"
        }
    }

    private fun normalizeScanValue(rawValue: String): String {
        val prefix = "https://www.dcrym.com?code="
        var value = rawValue.trim()
        if (value.startsWith(prefix)) {
            value = value.removePrefix(prefix)
            if (value.length > 2) {
                value = value.substring(2)
            }
        }
        return value
    }

    private fun requestWithRelogin(
        url: String,
        method: String,
        config: AppConfig,
        bodyJson: String? = null,
    ): ApiResponse {
        val first = requestJson(url, method, config, includeToken = true, bodyJson = bodyJson)
        if (isTokenInvalid(first)) {
            login(config)
            return requestJson(url, method, config, includeToken = true, bodyJson = bodyJson)
        }
        return first
    }

    private fun isTokenInvalid(response: ApiResponse): Boolean {
        if (response.code == -2) return true
        val msg = response.message
        return msg.contains("重新登录") || msg.contains("已登出") || msg.contains("会话已过期") || msg.contains("登陆过期")
    }

    private fun requestJson(
        url: String,
        method: String,
        config: AppConfig,
        includeToken: Boolean,
        bodyJson: String? = null,
    ): ApiResponse {
        val builder = Request.Builder()
            .url(url)
            .addHeader("key", "test")
            .addHeader("reqSource", "app")
            .addHeader("clientSource", buildClientSource(config))
            .addHeader("Content-Type", "application/json; charset=utf-8")

        if (includeToken) {
            builder.addHeader("token", config.token)
        }

        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post((bodyJson ?: "{}").toRequestBody(jsonType))
            else -> error("unsupported method: $method")
        }

        return execute(builder.build())
    }

    private fun buildClientSource(config: AppConfig): String {
        val customerId = config.effectiveCustomerId()
        val areaId = config.areaId
        val uuid = config.deviceUuid
        return """{"areaId":"$areaId","customerId":"$customerId","uuid":"$uuid","sourceType":"Android","appVersion":"4.3.116","platformCode":"00001","systemVersion":"android","deviceInfo":"android","networkInfo":"wifi|unknown"}"""
    }

    private fun execute(request: Request): ApiResponse {
        try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val root = JsonParser.parseString(raw).asJsonObject
                val data = root.get("data")
                return ApiResponse(
                    code = root.get("code")?.asInt ?: -9999,
                    message = root.get("msg")?.asString.orEmpty(),
                    data = data,
                    raw = raw,
                )
            }
        } catch (exc: IOException) {
            throw ApiError("网络错误：${exc.message.orEmpty()}")
        } catch (_: Exception) {
            throw ApiError("响应格式无效")
        }
    }
}

data class ApiResponse(
    val code: Int,
    val message: String,
    val data: JsonElement?,
    val raw: String,
) {
    val dataObject: JsonObject?
        get() = data.asJsonObjectOrNull()
    val dataArray: JsonArray?
        get() = data.asJsonArrayOrNull()
}

private fun JsonElement?.asJsonObjectOrNull(): JsonObject? =
    if (this != null && isJsonObject) asJsonObject else null

private fun JsonElement?.asJsonArrayOrNull(): JsonArray? =
    if (this != null && isJsonArray) asJsonArray else null

data class BalanceInfo(
    val amyBalance: String = "",
    val waterBean: String = "",
)
