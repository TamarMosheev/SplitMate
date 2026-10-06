package com.example.myapplication.data.api

/**
 * Single place to change the backend address.
 * Must be your computer's LAN IPv4 (physical phone, same Wi-Fi) and end with "/".
 * If the IP changes, also update res/xml/network_security_config.xml.
 */
object ApiConfig {
    const val BASE_URL = "http://172.30.33.169:8000/"
}
