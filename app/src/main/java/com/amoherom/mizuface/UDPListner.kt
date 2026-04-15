package com.amoherom.mizuface

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class UDPListner(
    private val onServerFound: (ip:String, port:String) -> Unit
) {

    private var running = true
    private var socket: DatagramSocket? = null

    fun start(port: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val s = DatagramSocket(port, InetAddress.getByName("0.0.0.0"))
                s.broadcast = true
                socket = s
                val buffer = ByteArray(2048)

                while (running) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        s.receive(packet)
                    } catch (e: Exception) {
                        if (!running) break  // closed by stop() — expected
                        throw e
                    }

                    val msg = String(packet.data, 0, packet.length)

                    if (msg.contains("iOSTrackingDataRequest")){
                        try{
                            val json = JSONObject(msg)
                            if (json.has("ports")) {
                                val ports = json.getJSONArray("ports")
                                if (ports.length() > 0){
                                    val port = ports.getInt(0)
                                    onServerFound(packet.address.toString(), port.toString())
                                }
                            }
                        } catch (_: Exception){

                        }

                    }


                }

                s.close()
            } catch (e: Exception){
                if (running) e.printStackTrace()  // ignore SocketException from stop()
            }
        }

    }

    fun stop() {
        running = false
        socket?.close()  // unblocks the blocked socket.receive() call
    }
}