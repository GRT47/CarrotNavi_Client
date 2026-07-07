package com.example.carrotnaviclient

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.carrotnaviclient.databinding.ActivityMainBinding
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.io.IOException
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolveListener: NsdManager.ResolveListener? = null
    private val SERVICE_TYPE = "_http._tcp."
    private val SERVICE_NAME = "carrotnavi"
    private var serverIp: String? = null
    private var serverPort: Int = 8080

    private val client = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val routeInfoRunnable = object : Runnable {
        override fun run() {
            fetchRouteInfo()
            mainHandler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        binding.tvAppVersion.text = "앱 버전: v${BuildConfig.VERSION_NAME}"

        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager

        setupUIListeners()
        startDiscovery()
        
        AutoUpdater.checkForUpdates(this)
    }

    private fun startDiscovery() {
        binding.tvConnectionStatus.text = "내비게이션 탐색 중..."
        
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType == SERVICE_TYPE && service.serviceName == SERVICE_NAME) {
                    nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            serverIp = serviceInfo.host.hostAddress
                            serverPort = serviceInfo.port
                            
                            // Save to SharedPreferences for ShareReceiverActivity
                            getSharedPreferences("ClientPrefs", Context.MODE_PRIVATE).edit().apply {
                                putString("SERVER_IP", serverIp)
                                putInt("SERVER_PORT", serverPort)
                                apply()
                            }
                            
                            mainHandler.post {
                                binding.tvConnectionStatus.text = "연결됨: ${serverIp}:${serverPort}"
                                binding.tvConnectionStatus.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
                                enableUI(true)
                                fetchSettings()
                                mainHandler.removeCallbacks(routeInfoRunnable)
                                fetchRouteInfo()
                                mainHandler.postDelayed(routeInfoRunnable, 2000)
                            }
                        }
                    })
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager?.stopServiceDiscovery(this)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager?.stopServiceDiscovery(this)
            }
        }
        
        try {
            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun enableUI(enabled: Boolean) {
        binding.swBoostEnable.isEnabled = enabled
        binding.sliderOffset.isEnabled = enabled
        binding.rbBoostProgressive.isEnabled = enabled
        binding.rbBoostFixed.isEnabled = enabled
        binding.sliderFakeDrop.isEnabled = enabled
        binding.swDebugOverlay.isEnabled = enabled
        binding.etTmapAppKey.isEnabled = enabled
        binding.etKakaoNativeAppKey.isEnabled = enabled
        binding.etKakaoRestApiKey.isEnabled = enabled
        binding.btnSaveApiKeys.isEnabled = enabled
    }

    private fun fetchSettings() {
        val ip = serverIp ?: return
        val request = Request.Builder()
            .url("http://$ip:$serverPort/api/settings")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    Toast.makeText(this@MainActivity, "설정 불러오기 실패", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                try {
                    val json = JSONObject(body)
                    val isBoostEnabled = json.optBoolean("BLOCK_SPEED_ENABLED", false)
                    val offset = json.optInt("BLOCK_SPEED_OFFSET", 0)
                    val fakeDrop = json.optInt("BLOCK_SPEED_FAKE_DROP", 10)
                    val boostMode = json.optInt("BLOCK_SPEED_BOOST_MODE", 0)
                    val isDebugOverlayVisible = json.optBoolean("DEBUG_OVERLAY_VISIBLE", false)
                    val appKey = json.optString("APP_KEY", "")
                    val kakaoNativeAppKey = json.optString("KAKAO_NATIVE_APP_KEY", "")
                    val kakaoRestApiKey = json.optString("KAKAO_REST_API_KEY", "")

                    mainHandler.post {
                        binding.swBoostEnable.isChecked = isBoostEnabled
                        binding.llBoostSettingsContainer.visibility = if (isBoostEnabled) android.view.View.VISIBLE else android.view.View.GONE
                        binding.sliderOffset.value = offset.toFloat()
                        binding.tvOffsetValue.text = "${offset} km/h"
                        binding.sliderFakeDrop.value = fakeDrop.toFloat()
                        binding.tvFakeDropValue.text = fakeDrop.toString()
                        binding.swDebugOverlay.isChecked = isDebugOverlayVisible
                        
                        binding.etTmapAppKey.setText(appKey)
                        binding.etKakaoNativeAppKey.setText(kakaoNativeAppKey)
                        binding.etKakaoRestApiKey.setText(kakaoRestApiKey)
                        
                        if (boostMode == 0) {
                            binding.rgBoostMode.check(binding.rbBoostProgressive.id)
                        } else {
                            binding.rgBoostMode.check(binding.rbBoostFixed.id)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        })
    }

    private fun fetchRouteInfo() {
        val ip = serverIp ?: return
        val request = Request.Builder()
            .url("http://$ip:$serverPort/api/route_info")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                try {
                    val json = JSONObject(body)
                    val szGoalName = json.optString("szGoalName", "")
                    val nGoPosDist = json.optInt("nGoPosDist", 0)
                    val nGoPosTime = json.optInt("nGoPosTime", 0)
                    
                    mainHandler.post {
                        if (szGoalName.isNotEmpty()) {
                            binding.llRouteInfoContainer.visibility = View.VISIBLE
                            binding.tvRouteDestination.text = "목적지: $szGoalName"
                            
                            val distText = if (nGoPosDist > 1000) String.format("%.1f km", nGoPosDist / 1000.0) else "$nGoPosDist m"
                            binding.tvRouteRemainDist.text = "남은 거리: $distText"
                            
                            val min = nGoPosTime / 60
                            val hour = min / 60
                            val remainMin = min % 60
                            val timeText = if (hour > 0) "${hour}시간 ${remainMin}분" else "${remainMin}분"
                            binding.tvRouteRemainTime.text = "소요 시간: $timeText"
                        } else {
                            binding.llRouteInfoContainer.visibility = View.GONE
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        })
    }

    private fun updateSettingOnServer(key: String, value: String) {
        val ip = serverIp ?: return
        
        val formBody = FormBody.Builder()
            .add(key, value)
            .build()
            
        val request = Request.Builder()
            .url("http://$ip:$serverPort/")
            .post(formBody)
            .build()
            
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {}
        })
    }

    private fun cancelRoute() {
        val ip = serverIp ?: return
        val formBody = FormBody.Builder().build()
        val request = Request.Builder()
            .url("http://$ip:$serverPort/api/cancel_route")
            .post(formBody)
            .build()
            
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    Toast.makeText(this@MainActivity, "취소 요청 실패", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onResponse(call: Call, response: Response) {
                mainHandler.post {
                    Toast.makeText(this@MainActivity, "안내를 취소했습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun setupUIListeners() {
        binding.swBoostEnable.setOnCheckedChangeListener { _, isChecked ->
            binding.llBoostSettingsContainer.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
            if (binding.swBoostEnable.isEnabled) {
                updateSettingOnServer("BLOCK_SPEED_ENABLED", if (isChecked) "true" else "false")
            }
        }
        
        binding.sliderOffset.addOnChangeListener { _, value, _ ->
            binding.tvOffsetValue.text = "${value.roundToInt()} km/h"
            updateSettingOnServer("BLOCK_SPEED_OFFSET", value.roundToInt().toString())
        }
        
        binding.sliderFakeDrop.addOnChangeListener { _, value, _ ->
            binding.tvFakeDropValue.text = value.roundToInt().toString()
            updateSettingOnServer("BLOCK_SPEED_FAKE_DROP", value.roundToInt().toString())
        }
        
        binding.rgBoostMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == binding.rbBoostProgressive.id) "0" else "1"
            updateSettingOnServer("BLOCK_SPEED_BOOST_MODE", mode)
        }
        
        binding.swDebugOverlay.setOnCheckedChangeListener { _, isChecked ->
            if (binding.swDebugOverlay.isEnabled) {
                updateSettingOnServer("DEBUG_OVERLAY_VISIBLE", if (isChecked) "true" else "false")
            }
        }
        
        binding.btnCancelRoute.setOnClickListener {
            cancelRoute()
        }

        binding.btnSaveApiKeys.setOnClickListener {
            val appKey = binding.etTmapAppKey.text.toString().trim()
            val kakaoNative = binding.etKakaoNativeAppKey.text.toString().trim()
            val kakaoRest = binding.etKakaoRestApiKey.text.toString().trim()
            
            val ip = serverIp ?: return@setOnClickListener
            val formBody = FormBody.Builder()
                .add("APP_KEY", appKey)
                .add("KAKAO_NATIVE_APP_KEY", kakaoNative)
                .add("KAKAO_REST_API_KEY", kakaoRest)
                .build()
                
            val request = Request.Builder()
                .url("http://$ip:$serverPort/")
                .post(formBody)
                .build()
                
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    mainHandler.post {
                        Toast.makeText(this@MainActivity, "API 키 저장 실패", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onResponse(call: Call, response: Response) {
                    mainHandler.post {
                        Toast.makeText(this@MainActivity, "API 키를 성공적으로 저장했습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(routeInfoRunnable)
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (e: Exception) {}
    }
}
