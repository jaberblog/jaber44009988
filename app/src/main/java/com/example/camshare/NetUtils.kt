package com.example.camshare

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {
    /** آی‌پی محلی گوشی در شبکه وای‌فای (یا هات‌اسپات) */
    fun localIp(): String? {
        return try {
            val candidates = NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { ni ->
                    ni.inetAddresses.toList()
                        .filter { it is Inet4Address && it.isSiteLocalAddress }
                        .map { ni.name to it.hostAddress }
                }
            candidates.firstOrNull { it.first.startsWith("wlan") || it.first.startsWith("ap") }?.second
                ?: candidates.firstOrNull()?.second
        } catch (e: Exception) {
            null
        }
    }
}
