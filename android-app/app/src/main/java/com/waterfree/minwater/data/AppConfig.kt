package com.waterfree.minwater.data

data class DeviceItem(
    var code: String = "",
    var position: String = "",
    var statusText: String = "",
    @Transient var isOpening: Boolean = false,
)

data class AppConfig(
    var account: String = "",
    var password: String = "",
    var customerIdOverride: String = "",
    var loginCustomerId: String = "",
    var areaId: String = "",
    var token: String = "",
    var deviceUuid: String = "",
    var autoEnterWaterPage: Boolean = false,
    var devices: MutableList<DeviceItem> = mutableListOf(),
) {
    fun effectiveCustomerId(): String = customerIdOverride.ifBlank { loginCustomerId }
}
