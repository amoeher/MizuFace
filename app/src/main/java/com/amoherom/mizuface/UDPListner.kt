package com.amoherom.mizuface

import android.content.Context
import android.util.Log
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

    fun start(port: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = DatagramSocket(port, InetAddress.getByName("0.0.0.0"))
                socket.broadcast = true
                val buffer = ByteArray(2048)

                while (running) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)

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
                        } catch (e: Exception){

                        }

                    }


                }

                socket.close()
            } catch (e: Exception){
                e.printStackTrace()
            }
        }.start()

    }

    fun stop() {
        running = false
    }
}