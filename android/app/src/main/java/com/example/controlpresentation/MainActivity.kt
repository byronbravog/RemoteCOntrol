package com.example.controlpresentation

import android.content.Context
import android.content.ComponentName
import android.graphics.Color
import android.net.Uri
import android.media.AudioManager
import android.os.Bundle
import android.os.PowerManager
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var lastActionText: TextView
    private lateinit var hostInput: EditText
    private lateinit var pinInput: EditText
    private lateinit var connectButton: Button
    private lateinit var volumeAccessButton: Button
    private lateinit var volumeAccessStatusText: TextView
    private lateinit var batteryOptimizationButton: Button
    private lateinit var previousButton: Button
    private lateinit var nextButton: Button
    private lateinit var startButton: Button
    private lateinit var blackButton: Button
    private lateinit var endButton: Button
    private lateinit var disconnectButton: Button

    private lateinit var audioManager: AudioManager
    private var connectedSocket: Socket? = null
    private var socketWriter: PrintWriter? = null
    private var socketReader: BufferedReader? = null
    private var connected = false
    private var controlsScreenVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        volumeControlStream = AudioManager.STREAM_MUSIC
        showConnectionScreen()
        startAutoDiscovery()
    }

    override fun onResume() {
        super.onResume()
        if (!controlsScreenVisible && ::volumeAccessStatusText.isInitialized &&
            ::volumeAccessButton.isInitialized && ::batteryOptimizationButton.isInitialized
        ) {
            refreshVolumeAccessStatus()
        }
    }

    private fun showConnectionScreen() {
        controlsScreenVisible = false
        setContentView(R.layout.activity_connection)

        statusText = findViewById(R.id.statusText)
        lastActionText = findViewById(R.id.lastActionText)
        hostInput = findViewById(R.id.hostInput)
        pinInput = findViewById(R.id.pinInput)
        connectButton = findViewById(R.id.connectButton)
        volumeAccessButton = findViewById(R.id.volumeAccessButton)
        volumeAccessStatusText = findViewById(R.id.volumeAccessStatusText)
        batteryOptimizationButton = findViewById(R.id.batteryOptimizationButton)

        setStatus("Desconectado", false)

        connectButton.setOnClickListener {
            val ip = hostInput.text.toString().trim()
            if (ip.isNotEmpty()) {
                connectToMac(ip)
            } else {
                startAutoDiscovery()
            }
        }
        volumeAccessButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        batteryOptimizationButton.setOnClickListener { requestBatteryOptimizationExemption() }
        refreshVolumeAccessStatus()
    }

    private fun showControlsScreen() {
        controlsScreenVisible = true
        setContentView(R.layout.activity_controls)
        statusText = findViewById(R.id.statusText)
        lastActionText = findViewById(R.id.lastActionText)
        previousButton = findViewById(R.id.previousButton)
        nextButton = findViewById(R.id.nextButton)
        startButton = findViewById(R.id.startButton)
        blackButton = findViewById(R.id.blackButton)
        endButton = findViewById(R.id.endButton)
        disconnectButton = findViewById(R.id.disconnectButton)

        previousButton.setOnClickListener { sendCommand("PREVIOUS") }
        nextButton.setOnClickListener { sendCommand("NEXT") }
        startButton.setOnClickListener { sendCommand("START") }
        blackButton.setOnClickListener { sendCommand("BLACK") }
        endButton.setOnClickListener { sendCommand("END") }
        disconnectButton.setOnClickListener { disconnect() }
    }

    private fun refreshVolumeAccessStatus() {
        val serviceComponent = ComponentName(this, VolumeKeyService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val enabled = Settings.Secure.getInt(
            contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        ) == 1 && enabledServices.split(':').any { it.equals(serviceComponent, ignoreCase = true) }

        volumeAccessStatusText.text = if (enabled) {
            "Botones de volumen listos para la pantalla bloqueada"
        } else {
            "Activa el servicio de accesibilidad para usar los botones con la pantalla bloqueada"
        }
        volumeAccessStatusText.setTextColor(
            if (enabled) Color.parseColor("#86EFAC") else Color.parseColor("#FCA5A5")
        )
        volumeAccessStatusText.setBackgroundColor(
            if (enabled) Color.parseColor("#14532D") else Color.parseColor("#7F1D1D")
        )
        volumeAccessButton.text = if (enabled) "Configurar accesibilidad" else "Activar botones de volumen"

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOptimizationExempt = powerManager.isIgnoringBatteryOptimizations(packageName)
        batteryOptimizationButton.text = if (batteryOptimizationExempt) {
            "Batería: sin restricciones"
        } else {
            "Permitir actividad con pantalla apagada"
        }
        batteryOptimizationButton.isEnabled = !batteryOptimizationExempt
    }

    private fun requestBatteryOptimizationExemption() {
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                sendCommand("NEXT")
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                sendCommand("PREVIOUS")
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun setStatus(message: String, connectedFlag: Boolean) {
        connected = connectedFlag
        if (controlsScreenVisible) {
            statusText.text = "●"
            statusText.contentDescription = if (connectedFlag) "Conectado" else "Desconectado"
            statusText.setTextColor(
                if (connectedFlag) Color.parseColor("#22C55E") else Color.parseColor("#64748B")
            )
            statusText.setBackgroundColor(Color.TRANSPARENT)
        } else {
            statusText.text = message
            statusText.setBackgroundColor(
                if (connectedFlag) Color.parseColor("#14532D") else Color.parseColor("#1E293B")
            )
        }
    }

    private fun updateLastAction(action: String) {
        lastActionText.text = "Última acción: $action"
    }

    private fun startAutoDiscovery() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = DatagramSocket()
                socket.broadcast = true
                socket.soTimeout = 2000

                val request = "DISCOVER".toByteArray(Charsets.UTF_8)
                val packet = DatagramPacket(
                    request,
                    request.size,
                    InetAddress.getByName("255.255.255.255"),
                    50001
                )
                socket.send(packet)

                val buffer = ByteArray(256)
                val receivePacket = DatagramPacket(buffer, buffer.size)
                socket.receive(receivePacket)

                val response = String(receivePacket.data, 0, receivePacket.length, Charsets.UTF_8)
                Log.d("DISCOVERY", "Respuesta: $response")

                if (response.startsWith("HELLO|")) {
                    val fields = response.split("|")
                    val host = fields.getOrNull(1).orEmpty()
                    val discoveredPin = fields.getOrNull(2).orEmpty()
                    runOnUiThread {
                        hostInput.setText(host)
                        if (discoveredPin.isNotEmpty()) {
                            pinInput.setText(discoveredPin)
                        }
                        Toast.makeText(this@MainActivity, "Mac encontrada: $host", Toast.LENGTH_SHORT).show()
                    }
                }

                socket.close()
            } catch (e: SocketTimeoutException) {
                Log.d("DISCOVERY", "No se encontró Mac en la red")
            } catch (e: Exception) {
                Log.e("DISCOVERY", "Error en auto discovery", e)
            }
        }
    }

    private fun connectToMac(host: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = Socket(host, 5001)
                socket.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)

                connectedSocket = socket
                socketWriter = writer
                socketReader = reader

                val welcome = reader.readLine() ?: ""
                Log.d("TCP", "Mensaje recibido: $welcome")

                if (welcome.startsWith("PIN:")) {
                    val pin = pinInput.text.toString().trim()
                    writer.println("PAIR $pin")
                    val authResponse = reader.readLine() ?: "AUTH_FAILED"

                    if (authResponse == "AUTHORIZED") {
                        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE).edit()
                            .putString(HOST_KEY, host)
                            .putString(PIN_KEY, pin)
                            .apply()
                        runOnUiThread {
                            showControlsScreen()
                            setStatus("Conectado", true)
                            updateLastAction("Conectado")
                            Toast.makeText(this@MainActivity, "Conexión autorizada", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        runOnUiThread {
                            setStatus("Desconectado", false)
                            updateLastAction("PIN incorrecto")
                            Toast.makeText(this@MainActivity, "PIN incorrecto", Toast.LENGTH_SHORT).show()
                        }
                        socket.close()
                    }
                } else {
                    runOnUiThread {
                        showControlsScreen()
                        setStatus("Conectado", true)
                        updateLastAction("Conectado")
                    }
                }

            } catch (e: Exception) {
                Log.e("TCP", "Error conectando con $host:5001", e)
                runOnUiThread {
                    showConnectionScreen()
                    setStatus("Desconectado", false)
                    updateLastAction("Error: ${e.message ?: "conexión rechazada"}")
                    Toast.makeText(this@MainActivity, "No se pudo conectar a $host", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun sendCommand(command: String) {
        if (connectedSocket == null || connectedSocket!!.isClosed) {
            updateLastAction("Sin conexión")
            Toast.makeText(this, "Conéctate primero a la Mac", Toast.LENGTH_SHORT).show()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val writer = socketWriter ?: throw IllegalStateException("socket no preparado")
                writer.println(command)
                val response = socketReader?.readLine() ?: throw IllegalStateException("servidor desconectado")
                if (response != "OK:$command") throw IllegalStateException(response)
                runOnUiThread {
                    updateLastAction(command)
                    Log.d("TCP_SEND", "Confirmado por Mac: $response")
                }
            } catch (e: Exception) {
                Log.e("TCP_SEND", "No se pudo enviar $command", e)
                runOnUiThread {
                    disconnect()
                    updateLastAction("Error al enviar")
                    Toast.makeText(this@MainActivity, "No se pudo enviar el comando", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun disconnect() {
        connected = false
        try { connectedSocket?.close() } catch (_: Exception) { }
        connectedSocket = null
        socketWriter = null
        socketReader = null
        showConnectionScreen()
    }

    companion object {
        const val PREFERENCES_NAME = "presenter_remote"
        const val HOST_KEY = "host"
        const val PIN_KEY = "pin"
    }
}
