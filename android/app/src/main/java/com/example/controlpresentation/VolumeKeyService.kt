package com.example.controlpresentation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.ServiceInfo
import android.content.Context
import android.content.Intent
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.media.AudioManager
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

class VolumeKeyService : AccessibilityService() {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "presentation_remote_service"
        private const val NOTIFICATION_ID = 1
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commandQueue = Channel<String>(Channel.UNLIMITED)

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: PrintWriter? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var commandWakeLock: PowerManager.WakeLock? = null
    private var commandWifiLock: WifiManager.WifiLock? = null
    private var mediaSession: MediaSession? = null
    private val releaseWifiLock = Runnable { commandWifiLock?.let { if (it.isHeld) it.release() } }

    private data class ServerCredentials(val host: String, val pin: String)

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        serviceInfo = info
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        commandWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ControlPresentationRemote:button-command"
        ).apply { setReferenceCounted(false) }
        val wifiManager = getSystemService(Context.WIFI_SERVICE) as WifiManager
        commandWifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL,
            "ControlPresentationRemote:button-command"
        ).apply { setReferenceCounted(false) }
        startAsForegroundService()
        setupMediaSession()
        Log.d("VolumeKeyService", "Conectado, flags=${info.flags}")

        // Worker que procesa la cola de comandos
        serviceScope.launch { processCommands() }
    }

    private fun startAsForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Control de presentación",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Control Presentación")
            .setContentText("Listo para recibir los botones de volumen")
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e("VolumeKeyService", "No se pudo iniciar el servicio en primer plano", e)
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return false
        val command = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> "NEXT"
            KeyEvent.KEYCODE_VOLUME_DOWN -> "PREVIOUS"
            else -> return false
        }
        return enqueueCommand(command, "accessibility")
    }

    private fun setupMediaSession() {
        mediaSession?.release()
        val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).setClassName(
            packageName,
            "androidx.media.session.MediaButtonReceiver"
        )
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val mediaButtonReceiver = PendingIntent.getBroadcast(
            this,
            0,
            mediaButtonIntent,
            pendingIntentFlags
        )
        mediaSession = MediaSession(this, "ControlPresentationRemote").apply {
            setMediaButtonReceiver(mediaButtonReceiver)
            setCallback(object : MediaSession.Callback() {}, mainHandler)
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setPlaybackToRemote(object : VolumeProvider(
                VOLUME_CONTROL_RELATIVE,
                100,
                50
            ) {
                override fun onAdjustVolume(direction: Int) {
                    val command = when (direction) {
                        AudioManager.ADJUST_RAISE -> "NEXT"
                        AudioManager.ADJUST_LOWER -> "PREVIOUS"
                        else -> return
                    }
                    enqueueCommand(command, "media-session")
                }
            })
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, 0L, 1.0f)
                    .build()
            )
            isActive = true
        }
    }

    private fun enqueueCommand(command: String, source: String): Boolean {
        Log.d("VolumeKeyService", "Encolando: $command ($source)")
        acquireCommandLocks()
        if (commandQueue.trySend(command).isFailure) {
            releaseCommandLocks()
            return false
        }
        return true
    }

    private fun acquireCommandLocks() {
        try {
            commandWakeLock?.let { lock ->
                if (lock.isHeld) lock.release()
                lock.acquire(15_000L)
            }
        } catch (e: Exception) {
            Log.w("VolumeKeyService", "No se pudo adquirir el bloqueo de CPU", e)
        }

        try {
            commandWifiLock?.let { lock ->
                if (!lock.isHeld) lock.acquire()
                mainHandler.removeCallbacks(releaseWifiLock)
                mainHandler.postDelayed(releaseWifiLock, 15_000L)
            }
        } catch (e: Exception) {
            Log.w("VolumeKeyService", "No se pudo adquirir el bloqueo de Wi-Fi", e)
        }
    }

    private fun releaseCommandLocks() {
        mainHandler.removeCallbacks(releaseWifiLock)
        try { commandWifiLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
        try { commandWakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
    }

    private suspend fun processCommands() {
        for (command in commandQueue) {
            try {
                ensureConnected()
                val activeWriter = writer ?: throw IOException("Conexión no preparada")
                activeWriter.println(command)
                activeWriter.flush()
                if (activeWriter.checkError()) throw IOException("Error enviando comando")
                val response = reader?.readLine() ?: throw IOException("Mac cerró la conexión")
                Log.d("VolumeKeyService", "Respuesta Mac: $response")
                if (response != "OK:$command") throw IOException("Respuesta inesperada: $response")
            } catch (e: Exception) {
                Log.e("VolumeKeyService", "Error enviando $command", e)
                closeConnection()
            }
        }
    }

    private fun ensureConnected() {
        if (socket?.isConnected == true && socket?.isClosed == false) return
        closeConnection()

        val prefs = getSharedPreferences(MainActivity.PREFERENCES_NAME, MODE_PRIVATE)
        val savedHost = prefs.getString(MainActivity.HOST_KEY, null)
        val savedPin = prefs.getString(MainActivity.PIN_KEY, null)

        if (!savedHost.isNullOrBlank() && !savedPin.isNullOrBlank()) {
            try {
                connectToServer(savedHost, savedPin)
                return
            } catch (e: Exception) {
                Log.w("VolumeKeyService", "Credenciales guardadas no válidas; buscando Mac", e)
                closeConnection()
            }
        }

        val discovered = discoverMac() ?: throw IOException("No se encontró la Mac en la red")
        connectToServer(discovered.host, discovered.pin)
        prefs.edit()
            .putString(MainActivity.HOST_KEY, discovered.host)
            .putString(MainActivity.PIN_KEY, discovered.pin)
            .apply()
    }

    private fun connectToServer(host: String, pin: String) {
        val candidate = Socket()
        try {
            candidate.connect(InetSocketAddress(host, 5001), 3000)
            candidate.soTimeout = 3000
            val candidateReader = BufferedReader(InputStreamReader(candidate.getInputStream()))
            val candidateWriter = PrintWriter(candidate.getOutputStream(), true)

            val welcome = candidateReader.readLine() ?: throw IOException("Servidor cerró")
            if (!welcome.startsWith("PIN:")) throw IOException("Protocolo inesperado: $welcome")
            candidateWriter.println("PAIR $pin")
            val auth = candidateReader.readLine() ?: throw IOException("Sin respuesta auth")
            if (auth != "AUTHORIZED") throw IOException("PIN rechazado")

            candidate.soTimeout = 5000
            socket = candidate
            reader = candidateReader
            writer = candidateWriter
            Log.d("VolumeKeyService", "Conexión persistente establecida con $host")
        } catch (e: Exception) {
            try { candidate.close() } catch (_: Exception) { }
            throw e
        }
    }

    private fun discoverMac(): ServerCredentials? {
        val discoverySocket = DatagramSocket()
        try {
            discoverySocket.broadcast = true
            discoverySocket.soTimeout = 2000
            val request = "DISCOVER".toByteArray(Charsets.UTF_8)
            discoverySocket.send(
                DatagramPacket(
                    request,
                    request.size,
                    InetAddress.getByName("255.255.255.255"),
                    50001
                )
            )

            val buffer = ByteArray(256)
            val response = DatagramPacket(buffer, buffer.size)
            discoverySocket.receive(response)
            val fields = String(response.data, 0, response.length, Charsets.UTF_8).split("|")
            if (fields.size != 3 || fields[0] != "HELLO" || fields[1].isBlank() || fields[2].isBlank()) {
                return null
            }
            return ServerCredentials(fields[1], fields[2])
        } catch (e: Exception) {
            Log.w("VolumeKeyService", "No se pudo descubrir la Mac", e)
            return null
        } finally {
            discoverySocket.close()
        }
    }

    private fun closeConnection() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; reader = null; writer = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        commandQueue.close()
        closeConnection()
        releaseCommandLocks()
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        serviceScope.cancel()
        super.onDestroy()
    }
}