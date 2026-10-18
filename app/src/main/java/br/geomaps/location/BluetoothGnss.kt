package br.geomaps.location

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import br.geomaps.core.NmeaFix
import br.geomaps.core.NmeaParser
import java.util.UUID

/** Receptor GNSS externo via Bluetooth clássico (perfil serial SPP) enviando NMEA 0183. */
class BluetoothGnss(private val context: Context) {
    companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        @SuppressLint("MissingPermission")
        fun paired(context: Context): List<Pair<String, String>> = runCatching {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bm.adapter?.bondedDevices?.map { (it.name ?: it.address) to it.address } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    var onFix: ((NmeaFix) -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var active = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        disconnect()
        active = true
        thread = Thread {
            val parser = NmeaParser()
            var attempt = 0
            while (active) {
                try {
                    val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                    val adapter: BluetoothAdapter = bm.adapter ?: throw IllegalStateException("Bluetooth indisponível")
                    val dev: BluetoothDevice = adapter.getRemoteDevice(address)
                    runCatching { adapter.cancelDiscovery() }
                    val s = runCatching { dev.createRfcommSocketToServiceRecord(SPP).also { it.connect() } }.getOrElse {
                        dev.createInsecureRfcommSocketToServiceRecord(SPP).also { it.connect() }
                    }
                    socket = s
                    attempt = 0
                    onStatus?.invoke("GNSS externo conectado: ${dev.name ?: address}")
                    val reader = s.inputStream.bufferedReader(Charsets.US_ASCII)
                    while (active) {
                        val line = reader.readLine() ?: break
                        parser.feed(line)?.let { onFix?.invoke(it) }
                    }
                } catch (e: Exception) {
                    if (!active) break
                    attempt++
                    onStatus?.invoke("GNSS externo: ${e.message ?: "falha na conexão"} — tentando novamente…")
                    try { Thread.sleep((2000L * attempt).coerceAtMost(15000)) } catch (_: InterruptedException) { break }
                } finally {
                    runCatching { socket?.close() }
                    socket = null
                }
            }
        }.apply { isDaemon = true; name = "gnss-bt"; start() }
    }

    fun disconnect() {
        active = false
        runCatching { socket?.close() }
        socket = null
        thread?.interrupt()
        thread = null
    }
}
