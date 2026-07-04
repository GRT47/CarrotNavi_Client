package com.example.carrotnaviclient

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit

class ShareReceiverActivity : Activity() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val SERVICE_TYPE = "_http._tcp."
    private val SERVICE_NAME = "carrotnavi"
    
    private var isSending = false
    private val timeoutRunnable = Runnable {
        if (!isSending) {
            stopDiscovery()
            tryCachedIpAndSend()
        }
    }
    
    private var sharedKeyword: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        handleSharedIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSharedIntent(intent)
    }

    private fun handleSharedIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            sharedKeyword = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (sharedKeyword != null) {
                startDiscoveryAndSend()
            } else {
                finish()
            }
        } else {
            finish()
        }
    }

    private fun startDiscoveryAndSend() {
        Toast.makeText(this, "내비 기기 찾는 중...", Toast.LENGTH_SHORT).show()
        
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType == SERVICE_TYPE && service.serviceName == SERVICE_NAME) {
                    nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            if (!isSending) {
                                isSending = true
                                mainHandler.removeCallbacks(timeoutRunnable)
                                stopDiscovery()
                                
                                val ip = serviceInfo.host.hostAddress
                                val port = serviceInfo.port
                                
                                // Update cache
                                getSharedPreferences("ClientPrefs", Context.MODE_PRIVATE).edit().apply {
                                    putString("SERVER_IP", ip)
                                    putInt("SERVER_PORT", port)
                                    apply()
                                }
                                
                                sendRequest(ip, port)
                            }
                        }
                    })
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                stopDiscovery()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                stopDiscovery()
            }
        }
        
        try {
            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            // Wait up to 2 seconds for NSD. If not found, try cached IP.
            mainHandler.postDelayed(timeoutRunnable, 2000)
        } catch (e: Exception) {
            e.printStackTrace()
            tryCachedIpAndSend()
        }
    }

    private fun tryCachedIpAndSend() {
        isSending = true
        val prefs = getSharedPreferences("ClientPrefs", Context.MODE_PRIVATE)
        val ip = prefs.getString("SERVER_IP", null)
        val port = prefs.getInt("SERVER_PORT", 8080)

        if (ip == null) {
            Toast.makeText(this, "기기를 찾을 수 없습니다. 앱을 실행해 먼저 연결해주세요.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        
        sendRequest(ip, port)
    }

    private fun sendRequest(ip: String, port: Int) {
        val keyword = sharedKeyword ?: return
        
        val formBody = FormBody.Builder()
            .add("search_text", keyword)
            .build()
            
        val request = Request.Builder()
            .url("http://$ip:$port/api/search")
            .post(formBody)
            .build()
            
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    Toast.makeText(this@ShareReceiverActivity, "목적지 전송 실패 (연결 오류)", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            override fun onResponse(call: Call, response: Response) {
                mainHandler.post {
                    if (response.isSuccessful) {
                        Toast.makeText(this@ShareReceiverActivity, "목적지 전송 완료!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@ShareReceiverActivity, "전송 실패 (${response.code})", Toast.LENGTH_SHORT).show()
                    }
                    finish()
                }
            }
        })
    }
    
    private fun stopDiscovery() {
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
            discoveryListener = null
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(timeoutRunnable)
        stopDiscovery()
    }
}
