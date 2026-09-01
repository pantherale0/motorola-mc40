package dev.pantherale0.mc40.ha

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.pantherale0.mc40.Mc40App
import dev.pantherale0.mc40.net.HttpClients
import dev.pantherale0.mc40.overlay.OverlayBus
import dev.pantherale0.mc40.overlay.OverlayParser
import dev.pantherale0.mc40.prefs.AppPrefs
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class NotifySocket(private val prefs: AppPrefs) {
    private val main = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    private var nextId = 2

    fun connect() {
        disconnect()
        if (!prefs.isRegistered) return
        val wsUrl = prefs.instanceUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://") + "/api/websocket"
        val request = Request.Builder().url(wsUrl).build()
        socket = HttpClients.okHttp.newWebSocket(request, Listener())
    }

    fun disconnect() {
        socket?.close(1000, "bye")
        socket = null
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = try {
                JsonParser.parseString(text).asJsonObject
            } catch (_: Exception) {
                return
            }
            when (json.get("type")?.asString) {
                "auth_required" -> {
                    val auth = JsonObject().apply {
                        addProperty("type", "auth")
                        addProperty("access_token", prefs.accessToken)
                    }
                    webSocket.send(auth.toString())
                }
                "auth_ok" -> {
                    val sub = JsonObject().apply {
                        addProperty("id", 1)
                        addProperty("type", "mobile_app/push_notification_channel")
                        addProperty("webhook_id", prefs.webhookId)
                        addProperty("support_confirm", false)
                    }
                    webSocket.send(sub.toString())
                    Log.i(Mc40App.TAG, "Notify websocket authenticated")
                }
                "event" -> handleEvent(json)
                "result" -> {
                    if (json.get("success")?.asBoolean == false) {
                        Log.w(Mc40App.TAG, "WS result error: ${text.take(200)}")
                    }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(Mc40App.TAG, "Notify websocket failed: ${t.message}")
            main.postDelayed({ if (prefs.isRegistered) connect() }, 10_000)
        }
    }

    private fun handleEvent(json: JsonObject) {
        val event = json.getAsJsonObject("event") ?: return
        val command = OverlayParser.parse(event)
        if (command != null) {
            Log.i(Mc40App.TAG, "Notify command ${command.action}")
            OverlayBus.post(command)
        } else {
            val title = event.get("title")?.asString.orEmpty()
            val message = event.get("message")?.asString.orEmpty()
            val display = listOf(title, message).filter { it.isNotEmpty() }.joinToString(" — ")
            if (display.isNotEmpty()) {
                main.post {
                    Toast.makeText(Mc40App.instance, display, Toast.LENGTH_LONG).show()
                }
            }
        }
        val confirmId = event.get("hass_confirm_id")?.asString
        if (!confirmId.isNullOrEmpty()) {
            val confirm = JsonObject().apply {
                addProperty("id", nextId++)
                addProperty("type", "mobile_app/push_notification_confirm")
                addProperty("webhook_id", prefs.webhookId)
                addProperty("confirm_id", confirmId)
            }
            socket?.send(confirm.toString())
        }
    }
}
