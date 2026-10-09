package radar.robot

import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MqttManager(
    private val host: String,
    private val port: Int,
    private val clientId: String,
    private val onState: (String) -> Unit,
    private val onLog: (String) -> Unit
) {

    @Volatile private var client: MqttClient? = null
    private val pool: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile var connected = false
        private set

    fun start() {
        pool.execute {
            try {
                val c = MqttClient("tcp://$host:$port", clientId, MemoryPersistence())
                val opts = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 10
                    keepAliveInterval = 20
                    automaticReconnect = true
                }
                c.connect(opts)
                client = c
                connected = true
                onState("connected")
            } catch (e: Exception) {
                connected = false
                onState("error: ${e.message}")
                onLog("MQTT connect failed: ${e.message}")
            }
        }
    }

    fun publish(topic: String, payload: String) {
        pool.execute {
            try {
                val c = client ?: return@execute
                if (!c.isConnected) return@execute
                val msg = MqttMessage(payload.toByteArray(Charsets.UTF_8))
                msg.qos = 1
                msg.isRetained = false
                c.publish(topic, msg)
            } catch (e: Exception) {
                onLog("publish err: ${e.message}")
            }
        }
    }

    fun sendCommand(topic: String, command: String) {
        publish(topic, command)
    }

    fun stop() {
        pool.execute {
            try { client?.disconnect() } catch (e: Exception) {}
            client = null
            connected = false
            onState("stopped")
        }
        pool.shutdown()
    }
}