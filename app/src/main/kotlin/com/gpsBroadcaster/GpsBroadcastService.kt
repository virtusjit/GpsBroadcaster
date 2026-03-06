package com.gpsbroadcaster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList

class GpsBroadcastService : LifecycleService() {

    companion object {
        const val TAG = "GpsBroadcastService"
        const val CHANNEL_ID = "gps_broadcast_channel"
        const val NOTIFICATION_ID = 1

        const val EXTRA_PORT = "extra_port"
        const val DEFAULT_PORT = 50000

        const val ACTION_STATUS = "com.gpsbroadcaster.STATUS"
        const val EXTRA_CLIENT_COUNT = "client_count"
        const val EXTRA_LAST_NMEA = "last_nmea"
        const val EXTRA_SERVER_IP = "server_ip"
        const val EXTRA_BIND_ADDRESS = "bind_address"
        const val EXTRA_PROVIDER = "extra_provider"
        const val EXTRA_SATELLITES_USED = "sats_used"
        const val EXTRA_SATELLITES_TOTAL = "sats_total"
        const val EXTRA_ACCURACY = "accuracy"
        const val EXTRA_SPOOF_FLAG = "spoof_flag"

        const val MAX_ACCURACY_METERS = 500f
        const val SPOOF_ALTITUDE_MIN = -100.0
        const val SPOOF_ALTITUDE_MAX = 2000.0
        const val SPOOF_JUMP_METERS = 5000.0
        const val ANCHOR_MAX_DISTANCE = 10_000f
    }

    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<PrintWriter>()
    private var serverJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var port = DEFAULT_PORT
    private var bindAddress = "0.0.0.0"
    private var providerMode = "fused"
    private var lastNmea = ""
    @Volatile private var detectedServerIp: String? = null

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var locationManager: LocationManager
    private var satelliteCount = 0
    private var usedSatelliteCount = 0
    private var lastValidLocation: Location? = null
    @Volatile private var spoofDetected = false
    @Volatile private var lastAccuracy = 0f
    @Volatile private var satelliteInfoList: List<SatInfo> = emptyList()
    @Volatile private var networkAnchor: Location? = null

    data class SatInfo(
        val prn: Int, val elevation: Float, val azimuth: Float,
        val snr: Float, val usedInFix: Boolean, val constellation: Int
    )

    private val networkAnchorListener = android.location.LocationListener { loc ->
        networkAnchor = loc
        Log.i(TAG, "Network anchor updated: ${loc.latitude}, ${loc.longitude}, accuracy=${loc.accuracy}m")
    }

    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            processLocation(loc)
        }
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            satelliteCount = status.satelliteCount
            var used = 0
            val sats = mutableListOf<SatInfo>()
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) used++
                sats.add(SatInfo(
                    prn = status.getSvid(i),
                    elevation = status.getElevationDegrees(i),
                    azimuth = status.getAzimuthDegrees(i),
                    snr = status.getCn0DbHz(i),
                    usedInFix = status.usedInFix(i),
                    constellation = status.getConstellationType(i)
                ))
            }
            usedSatelliteCount = used
            satelliteInfoList = sats
        }
    }

    private fun processLocation(loc: Location) {
        lastAccuracy = if (loc.hasAccuracy()) loc.accuracy else 0f

        if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY_METERS) {
            Log.d(TAG, "Skipping low-accuracy fix: ${loc.accuracy}m (provider: ${loc.provider})")
            sendStatusBroadcast()
            return
        }

        var spoof = false

        val anchor = networkAnchor
        if (anchor != null) {
            val distToAnchor = loc.distanceTo(anchor)
            val threshold = ANCHOR_MAX_DISTANCE + anchor.accuracy
            if (distToAnchor > threshold) {
                Log.w(TAG, "Location ${distToAnchor.toInt()}m from network anchor (threshold ${threshold.toInt()}m) — spoofed GPS, using anchor fallback")
                spoof = true
            }
        }

        if (!spoof && loc.hasAltitude()) {
            val alt = loc.altitude
            if (alt < SPOOF_ALTITUDE_MIN || alt > SPOOF_ALTITUDE_MAX) {
                Log.w(TAG, "Suspicious altitude: ${alt}m — possible spoofing")
                spoof = true
            }
        }

        if (!spoof) {
            val prev = lastValidLocation
            if (prev != null) {
                val dist = prev.distanceTo(loc)
                val dt = (loc.time - prev.time) / 1000.0
                if (dt > 0 && dist > SPOOF_JUMP_METERS && dist / dt > 340) {
                    Log.w(TAG, "Position jump ${dist.toInt()}m in ${dt.toInt()}s — possible spoofing")
                    spoof = true
                }
            }
        }

        spoofDetected = spoof

        val useLoc = if (spoof && anchor != null) {
            Log.i(TAG, "Fallback to network anchor: ${anchor.latitude}, ${anchor.longitude}, accuracy=${anchor.accuracy}m")
            anchor
        } else if (spoof) {
            sendStatusBroadcast()
            return
        } else {
            loc
        }

        lastValidLocation = useLoc
        val gnrmc = buildGnrmc(useLoc)
        val gpvtg = buildGpvtg(useLoc)
        val gngga = buildGngga(useLoc)
        val gngsa = buildGngsa(useLoc)
        val gngsv = buildGngsv()
        val gngll = buildGngll(useLoc)
        broadcastNmea(gnrmc)
        broadcastNmea(gpvtg)
        broadcastNmea(gngga)
        broadcastNmea(gngsa)
        for (gsv in gngsv) broadcastNmea(gsv)
        broadcastNmea(gngll)
        lastNmea = gnrmc
        sendStatusBroadcast()
    }

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        port = intent?.getIntExtra(EXTRA_PORT, DEFAULT_PORT) ?: DEFAULT_PORT
        bindAddress = intent?.getStringExtra(EXTRA_BIND_ADDRESS) ?: "0.0.0.0"
        providerMode = intent?.getStringExtra(EXTRA_PROVIDER) ?: "fused"

        startForeground(NOTIFICATION_ID, buildNotification("Запуск сервера на порту $port..."))

        if (serverJob?.isActive == true) {
            Log.i(TAG, "Server already running, skipping duplicate start")
            return START_STICKY
        }

        acquireLocks()
        startServer()
        startLocationUpdates()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
        stopLocationUpdates()
        releaseLocks()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    // ── Location Updates ──────────────────────────────────────────────────────

    private fun startLocationUpdates() {
        try {
            locationManager.registerGnssStatusCallback(gnssCallback, android.os.Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot register GNSS status: ${e.message}")
        }

        try {
            if (locationManager.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 30_000L, 100f, networkAnchorListener
                )
                val last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (last != null) {
                    networkAnchor = last
                    Log.i(TAG, "Initial network anchor: ${last.latitude}, ${last.longitude}")
                }
                Log.i(TAG, "Network anchor listener registered (30s interval)")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot get network location for anchor: ${e.message}")
        }

        try {
            val priority = when (providerMode) {
                "network" -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
                else -> Priority.PRIORITY_HIGH_ACCURACY
            }
            val request = LocationRequest.Builder(priority, 1000L)
                .setMinUpdateIntervalMillis(500L)
                .setWaitForAccurateLocation(false)
                .build()

            fusedClient.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())
            Log.i(TAG, "FusedLocation started, mode=$providerMode, priority=$priority")
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission denied: ${e.message}")
        }
    }

    private fun stopLocationUpdates() {
        fusedClient.removeLocationUpdates(fusedCallback)
        try {
            locationManager.removeUpdates(networkAnchorListener)
            locationManager.unregisterGnssStatusCallback(gnssCallback)
        } catch (_: Exception) {}
    }

    // ── TCP Server ───────────────────────────────────────────────────────────

    private fun startServer() {
        serverJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val addr = InetAddress.getByName(bindAddress)
                serverSocket = ServerSocket(port, 50, addr)
                Log.i(TAG, "TCP server listening on $bindAddress:$port")
                updateNotification("Сервер запущен: порт $port")
                while (isActive) {
                    val client: Socket = serverSocket!!.accept()
                    client.soTimeout = 0
                    detectedServerIp = client.localAddress?.hostAddress
                    Log.i(TAG, "Client connected: ${client.inetAddress.hostAddress}, server IP: $detectedServerIp")
                    launch { handleClient(client) }
                    sendStatusBroadcast()
                }
            } catch (e: Exception) {
                if (isActive) Log.e(TAG, "Server error: ${e.message}")
            }
        }
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        val writer = PrintWriter(socket.getOutputStream(), true)
        clients.add(writer)
        sendStatusBroadcast()
        try {
            val input = socket.getInputStream()
            while (isActive) {
                if (input.read() == -1) break
            }
        } catch (_: Exception) {
        } finally {
            clients.remove(writer)
            runCatching { socket.close() }
            Log.i(TAG, "Client disconnected: ${socket.inetAddress.hostAddress}")
            sendStatusBroadcast()
        }
    }

    private fun broadcastNmea(nmea: String) {
        val line = if (nmea.endsWith("\r\n")) nmea else "$nmea\r\n"
        lifecycleScope.launch(Dispatchers.IO) {
            val deadClients = mutableListOf<PrintWriter>()
            for (writer in clients) {
                try {
                    writer.print(line)
                    writer.flush()
                    if (writer.checkError()) deadClients.add(writer)
                } catch (_: Exception) {
                    deadClients.add(writer)
                }
            }
            clients.removeAll(deadClients)
        }
    }

    private fun stopServer() {
        serverJob?.cancel()
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    // ── Status Broadcast ─────────────────────────────────────────────────────

    private fun sendStatusBroadcast() {
        val intent = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_CLIENT_COUNT, clients.size)
            putExtra(EXTRA_LAST_NMEA, lastNmea)
            putExtra(EXTRA_SERVER_IP, getHotspotIp())
            putExtra(EXTRA_SATELLITES_USED, usedSatelliteCount)
            putExtra(EXTRA_SATELLITES_TOTAL, satelliteCount)
            putExtra(EXTRA_ACCURACY, lastAccuracy)
            putExtra(EXTRA_SPOOF_FLAG, spoofDetected)
        }
        sendBroadcast(intent)
    }

    private fun getHotspotIp(): String {
        return detectedServerIp ?: bindAddress
    }

    // ── NMEA Generation (GN format matching GW GPS по WiFi) ─────────────────

    private fun buildGnrmc(loc: Location): String {
        val time = utcTime(loc.time)
        val date = utcDate(loc.time)
        val lat = toNmeaLat(loc.latitude)
        val latDir = if (loc.latitude >= 0) "N" else "S"
        val lon = toNmeaLon(loc.longitude)
        val lonDir = if (loc.longitude >= 0) "E" else "W"
        val speed = if (loc.hasSpeed()) fmt("%.3f", loc.speed * 1.94384) else "0.000"
        val bearing = if (loc.hasBearing()) fmt("%.1f", loc.bearing.toDouble()) else ""
        val body = "GNRMC,$time,A,$lat,$latDir,$lon,$lonDir,$speed,$bearing,$date,,,A,V"
        return "\$$body*${nmeaChecksum(body)}"
    }

    private fun buildGpvtg(loc: Location): String {
        val courseT = if (loc.hasBearing()) fmt("%.1f", loc.bearing.toDouble()) else ""
        val speedKn = if (loc.hasSpeed()) fmt("%.3f", loc.speed * 1.94384) else "0.000"
        val speedKm = if (loc.hasSpeed()) fmt("%.3f", loc.speed * 3.6) else "0.000"
        val body = "GPVTG,$courseT,T,,M,$speedKn,N,$speedKm,K,"
        return "\$$body*${nmeaChecksum(body)}"
    }

    private fun buildGngga(loc: Location): String {
        val time = utcTime(loc.time)
        val lat = toNmeaLat(loc.latitude)
        val latDir = if (loc.latitude >= 0) "N" else "S"
        val lon = toNmeaLon(loc.longitude)
        val lonDir = if (loc.longitude >= 0) "E" else "W"
        val alt = if (loc.hasAltitude()) fmt("%.1f", loc.altitude) else ""
        val sats = if (usedSatelliteCount > 0) usedSatelliteCount else if (satelliteCount > 0) satelliteCount else 0
        val hdop = if (loc.hasAccuracy()) fmt("%.2f", loc.accuracy / 5.0) else ""
        val body = "GNGGA,$time,$lat,$latDir,$lon,$lonDir,1,${String.format("%02d", sats)},$hdop,$alt,M,,M,,"
        return "\$$body*${nmeaChecksum(body)}"
    }

    private fun buildGngsa(loc: Location): String {
        val prns = satelliteInfoList
            .filter { it.usedInFix }
            .take(12)
            .joinToString(",") { String.format("%02d", it.prn) }
        val prnPadded = if (prns.isEmpty()) ",,,,,,,,,,," else {
            val used = prns.split(",")
            (used + List(12 - used.size) { "" }).joinToString(",")
        }
        val hdop = if (loc.hasAccuracy()) fmt("%.2f", loc.accuracy / 5.0) else ""
        val vdop = if (loc.hasAccuracy()) fmt("%.2f", loc.accuracy / 4.5) else ""
        val pdop = if (loc.hasAccuracy()) fmt("%.2f", loc.accuracy / 3.5) else ""
        val fix = if (usedSatelliteCount >= 4) "3" else if (usedSatelliteCount >= 3) "2" else "1"
        val body = "GNGSA,A,$fix,$prnPadded,$pdop,$hdop,$vdop,1"
        return "\$$body*${nmeaChecksum(body)}"
    }

    private fun buildGngsv(): List<String> {
        val sats = satelliteInfoList.ifEmpty {
            return listOf(nmeaSentence("GNGSV,1,1,00"))
        }
        val perMsg = 4
        val totalMsgs = (sats.size + perMsg - 1) / perMsg
        val result = mutableListOf<String>()
        for (msgIdx in 0 until totalMsgs) {
            val sb = StringBuilder()
            sb.append("GNGSV,$totalMsgs,${msgIdx + 1},${String.format("%02d", sats.size)}")
            val start = msgIdx * perMsg
            val end = minOf(start + perMsg, sats.size)
            for (i in start until end) {
                val s = sats[i]
                sb.append(",${String.format("%02d", s.prn)}")
                sb.append(",${String.format("%02d", s.elevation.toInt())}")
                sb.append(",${String.format("%03d", s.azimuth.toInt())}")
                sb.append(",${String.format("%02d", s.snr.toInt())}")
            }
            sb.append(",1")
            result.add(nmeaSentence(sb.toString()))
        }
        return result
    }

    private fun buildGngll(loc: Location): String {
        val lat = toNmeaLat(loc.latitude)
        val latDir = if (loc.latitude >= 0) "N" else "S"
        val lon = toNmeaLon(loc.longitude)
        val lonDir = if (loc.longitude >= 0) "E" else "W"
        val time = utcTime(loc.time)
        val body = "GNGLL,$lat,$latDir,$lon,$lonDir,$time,A,A"
        return "\$$body*${nmeaChecksum(body)}"
    }

    private fun nmeaSentence(body: String) = "\$$body*${nmeaChecksum(body)}"

    // ── NMEA Utilities ───────────────────────────────────────────────────────

    private fun utcTime(timestamp: Long): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = timestamp }
        return String.format(
            Locale.US, "%02d%02d%02d.%03d",
            cal[Calendar.HOUR_OF_DAY], cal[Calendar.MINUTE],
            cal[Calendar.SECOND], cal[Calendar.MILLISECOND]
        )
    }

    private fun utcDate(timestamp: Long): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = timestamp }
        return String.format(
            Locale.US, "%02d%02d%02d",
            cal[Calendar.DAY_OF_MONTH], cal[Calendar.MONTH] + 1, cal[Calendar.YEAR] % 100
        )
    }

    private fun toNmeaLat(lat: Double): String {
        val abs = Math.abs(lat)
        val deg = abs.toInt()
        return String.format(Locale.US, "%02d%08.5f", deg, (abs - deg) * 60.0)
    }

    private fun toNmeaLon(lon: Double): String {
        val abs = Math.abs(lon)
        val deg = abs.toInt()
        return String.format(Locale.US, "%03d%08.5f", deg, (abs - deg) * 60.0)
    }

    private fun nmeaChecksum(sentence: String): String {
        var cs = 0
        for (c in sentence) cs = cs xor c.code
        return String.format("%02X", cs)
    }

    private fun fmt(pattern: String, value: Double) = String.format(Locale.US, pattern, value)

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "GPS Broadcaster",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Трансляция GPS по TCP" }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS Broadcaster")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ── Wake / Wi-Fi locks ────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG::WakeLock").apply {
            acquire(12 * 60 * 60 * 1000L)
        }
        val wm = getSystemService(WIFI_SERVICE) as WifiManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "$TAG::WifiLock").apply { acquire() }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
    }
}
