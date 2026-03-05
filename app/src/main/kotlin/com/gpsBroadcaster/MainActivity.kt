package com.gpsbroadcaster

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.gpsbroadcaster.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isRunning = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != GpsBroadcastService.ACTION_STATUS) return
            val clients  = intent.getIntExtra(GpsBroadcastService.EXTRA_CLIENT_COUNT, 0)
            val nmea     = intent.getStringExtra(GpsBroadcastService.EXTRA_LAST_NMEA) ?: ""
            val ip       = intent.getStringExtra(GpsBroadcastService.EXTRA_SERVER_IP) ?: "N/A"
            val satsUsed = intent.getIntExtra(GpsBroadcastService.EXTRA_SATELLITES_USED, 0)
            val satsAll  = intent.getIntExtra(GpsBroadcastService.EXTRA_SATELLITES_TOTAL, 0)
            val accuracy = intent.getFloatExtra(GpsBroadcastService.EXTRA_ACCURACY, 0f)
            val spoof    = intent.getBooleanExtra(GpsBroadcastService.EXTRA_SPOOF_FLAG, false)
            updateStatusUi(clients, nmea, ip, satsUsed, satsAll, accuracy, spoof)
        }
    }

    // ── Permission Launchers ──────────────────────────────────────────────────

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* notification permission is non-blocking; service works regardless */ }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val fineGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (!fineGranted) {
            Toast.makeText(this, "Разрешение на геолокацию обязательно!", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        // On Android 10+ background location must be requested separately
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(
                    this,
                    "Для работы в фоне откройте настройки и выберите «Всегда»",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                )
                return@registerForActivityResult
            }
        }
        doStartService()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnToggle.setOnClickListener {
            if (isRunning) stopBroadcast() else startBroadcast()
        }

        binding.btnOpenSettings.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            )
        }

        populateProviderSelector()

        binding.tvNmeaToggle.setOnClickListener {
            val nmea = binding.tvLastNmea
            if (nmea.visibility == View.GONE) {
                nmea.visibility = View.VISIBLE
                binding.tvNmeaToggle.text = "NMEA ▼"
            } else {
                nmea.visibility = View.GONE
                binding.tvNmeaToggle.text = "NMEA ▶"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(GpsBroadcastService.ACTION_STATUS)
        ContextCompat.registerReceiver(this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        populateIpSelector()
    }

    private val providerOptions = linkedMapOf(
        "fused" to "Умный (GPS + WiFi + вышки + датчики) ★",
        "gps" to "Только GPS спутники",
        "network" to "Только сеть (WiFi + вышки)"
    )

    private fun populateProviderSelector() {
        val labels = providerOptions.values.toList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels)
        binding.actvProvider.setAdapter(adapter)
        binding.actvProvider.setText(labels[0], false)
    }

    private fun getSelectedProvider(): String {
        val selected = binding.actvProvider.text?.toString() ?: ""
        return providerOptions.entries.find { it.value == selected }?.key ?: "gps"
    }

    private fun populateIpSelector() {
        val ips = getAvailableIps()
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, ips)
        binding.actvIp.setAdapter(adapter)
        if (binding.actvIp.text.isNullOrEmpty() || binding.actvIp.text.toString() !in ips) {
            binding.actvIp.setText(ips.firstOrNull() ?: "", false)
        }
    }

    private fun getAvailableIps(): List<String> {
        val result = mutableListOf<String>()
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return result
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr.isLoopbackAddress || addr !is java.net.Inet4Address) continue
                    val ip = addr.hostAddress ?: continue
                    result.add("$ip  (${iface.displayName})")
                }
            }
        } catch (_: Exception) {}
        if (result.isEmpty()) result.add("0.0.0.0  (все интерфейсы)")
        return result
    }

    private fun getSelectedIp(): String {
        val text = binding.actvIp.text?.toString() ?: return "0.0.0.0"
        return text.substringBefore("  ").trim()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    // ── Service control ───────────────────────────────────────────────────────

    private fun startBroadcast() {
        val port = binding.etPort.text.toString().toIntOrNull()
            ?.takeIf { it in 1024..65535 }
            ?: GpsBroadcastService.DEFAULT_PORT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val notGranted = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        } else {
            doStartService(port)
        }
    }

    private fun doStartService(
        port: Int = binding.etPort.text.toString().toIntOrNull()
            ?.takeIf { it in 1024..65535 } ?: GpsBroadcastService.DEFAULT_PORT
    ) {
        val bindIp = getSelectedIp()
        val provider = getSelectedProvider()
        val intent = Intent(this, GpsBroadcastService::class.java).apply {
            putExtra(GpsBroadcastService.EXTRA_PORT, port)
            putExtra(GpsBroadcastService.EXTRA_BIND_ADDRESS, bindIp)
            putExtra(GpsBroadcastService.EXTRA_PROVIDER, provider)
        }
        ContextCompat.startForegroundService(this, intent)
        isRunning = true
        binding.etPort.isEnabled = false
        binding.actvIp.isEnabled = false
        binding.actvProvider.isEnabled = false
        binding.btnToggle.text = "Остановить"
        binding.statusCard.visibility = View.VISIBLE
        binding.tvServerPort.text = "Порт: $port"
        binding.tvServerIp.text = "IP: $bindIp"
    }

    private fun stopBroadcast() {
        stopService(Intent(this, GpsBroadcastService::class.java))
        isRunning = false
        binding.etPort.isEnabled = true
        binding.actvIp.isEnabled = true
        binding.actvProvider.isEnabled = true
        binding.btnToggle.text = "Запустить"
        binding.tvClientCount.text = "Клиенты: 0"
        binding.tvSatellites.text = "Спутники: —"
        binding.tvAccuracy.text = "Точность: —"
        binding.tvSpoofFlag.text = "Спуфинг: не обнаружен"
        binding.tvSpoofFlag.setTextColor(getColor(R.color.text_primary))
        binding.tvLastNmea.text = "—"
        binding.tvLastNmea.visibility = View.GONE
        binding.tvNmeaToggle.text = "NMEA ▶"
        binding.tvServerIp.text = "IP: —"
        populateIpSelector()
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    private fun updateStatusUi(
        clients: Int, nmea: String, ip: String,
        satsUsed: Int, satsAll: Int, accuracy: Float, spoof: Boolean
    ) {
        binding.tvServerIp.text    = "IP: $ip"
        binding.tvClientCount.text = "Клиенты: $clients"
        binding.tvSatellites.text  = "Спутники: $satsUsed из $satsAll"
        binding.tvAccuracy.text    = if (accuracy > 0) "Точность: ${accuracy.toInt()} м" else "Точность: —"
        binding.tvLastNmea.text    = if (nmea.length > 70) nmea.take(70) + "…" else nmea

        if (spoof) {
            binding.tvSpoofFlag.text = "Спуфинг: ОБНАРУЖЕН"
            binding.tvSpoofFlag.setTextColor(getColor(R.color.spoof_warn))
        } else {
            binding.tvSpoofFlag.text = "Спуфинг: не обнаружен"
            binding.tvSpoofFlag.setTextColor(getColor(R.color.text_primary))
        }
    }
}
