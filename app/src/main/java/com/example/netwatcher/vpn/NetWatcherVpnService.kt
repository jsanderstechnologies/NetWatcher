package com.example.netwatcher.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.netwatcher.MainActivity
import com.example.netwatcher.model.TrafficLogEntry
import com.example.netwatcher.repository.TrafficRepository
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class NetWatcherVpnService : VpnService() {

    companion object {
        const val TAG = "NetWatcherVpn"
        const val ACTION_START = "com.example.netwatcher.START_VPN"
        const val ACTION_STOP = "com.example.netwatcher.STOP_VPN"
        const val EXTRA_TARGET_PACKAGE = "target_package_name"
        const val EXTRA_TARGET_APP_NAME = "target_app_name"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "netwatcher_channel"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val isRunning = AtomicBoolean(false)
    private var workerExecutor: ExecutorService? = null

    private var targetPackage: String? = null
    private var targetAppName: String = "All Applications"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }

        if (action == ACTION_START || intent != null) {
            targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
            targetAppName = intent.getStringExtra(EXTRA_TARGET_APP_NAME) ?: "All Applications"

            startForegroundServiceNotification()
            startVpn()
        }

        return START_STICKY
    }

    private fun startForegroundServiceNotification() {
        createNotificationChannel()

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NetWatcher Active")
            .setContentText("Monitoring traffic for: $targetAppName")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NetWatcher Traffic Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification displayed while monitoring application network traffic"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startVpn() {
        if (isRunning.get()) return

        try {
            val builder = Builder()
                .setSession("NetWatcher")
                .addAddress("10.1.10.1", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
                .setMtu(1500)

            // Scoping traffic strictly to the target application if selected
            val targetPkg = targetPackage
            if (!targetPkg.isNullOrEmpty()) {
                try {
                    builder.addAllowedApplication(targetPkg)
                    Log.i(TAG, "Restricted VPN capture to target package: $targetPkg")
                } catch (e: Exception) {
                    Log.e(TAG, "Could not restrict VPN to package $targetPkg", e)
                }
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                return
            }

            isRunning.set(true)
            TrafficRepository.setMonitoring(true)

            workerExecutor = Executors.newFixedThreadPool(2)
            workerExecutor?.submit { runPacketLoop() }

            Log.i(TAG, "VPN service started successfully for $targetAppName")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN service", e)
            stopVpn()
        }
    }

    private fun runPacketLoop() {
        val pfd = vpnInterface ?: return
        val inputStream = FileInputStream(pfd.fileDescriptor)
        val outputStream = FileOutputStream(pfd.fileDescriptor)
        val buffer = ByteArray(32767)

        while (isRunning.get()) {
            try {
                val length = inputStream.read(buffer)
                if (length > 0) {
                    val parsed = PacketParser.parse(buffer, length)
                    if (parsed != null && parsed.destinationIp.isNotEmpty()) {

                        // Filter out loopback / local subnet noise
                        if (parsed.destinationIp != "10.1.10.1") {
                            val logEntry = TrafficLogEntry(
                                packageName = targetPackage ?: "all_apps",
                                appName = targetAppName,
                                domain = parsed.domain,
                                destinationIp = parsed.destinationIp,
                                destinationPort = parsed.destinationPort,
                                sourceIp = parsed.sourceIp,
                                sourcePort = parsed.sourcePort,
                                protocol = parsed.protocol,
                                packetSize = length
                            )

                            TrafficRepository.addLog(logEntry)

                            // Process packet forwarding / DNS relay if needed
                            if (parsed.protocol == "UDP" && parsed.destinationPort == 53) {
                                relayDnsPacket(buffer, length, outputStream)
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                if (isRunning.get()) {
                    Log.e(TAG, "Error reading from VPN interface", e)
                }
                break
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error in packet loop", e)
            }
        }
    }

    private fun relayDnsPacket(buffer: ByteArray, length: Int, outputStream: FileOutputStream) {
        // Simple UDP DNS proxy loop using protected socket
        try {
            val socket = DatagramSocket()
            protect(socket)
            socket.soTimeout = 2000

            val ihl = (buffer[0].toInt() and 0x0F) * 4
            val dnsPayloadStart = ihl + 8
            val dnsPayloadSize = length - dnsPayloadStart

            if (dnsPayloadSize > 0) {
                val dnsData = buffer.copyOfRange(dnsPayloadStart, length)
                val googleDns = InetAddress.getByName("8.8.8.8")
                val outPacket = DatagramPacket(dnsData, dnsData.size, googleDns, 53)

                socket.send(outPacket)

                val respBuffer = ByteArray(1500)
                val inPacket = DatagramPacket(respBuffer, respBuffer.size)
                socket.receive(inPacket)

                // Parse DNS response to populate IP -> Domain cache
                PacketParser.parse(respBuffer, inPacket.length)
            }
            socket.close()
        } catch (_: Exception) {
            // Silently ignore DNS timeout/relay failure
        }
    }

    private fun stopVpn() {
        isRunning.set(false)
        TrafficRepository.setMonitoring(false)

        try {
            workerExecutor?.shutdownNow()
            workerExecutor = null

            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "VPN service stopped")
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
