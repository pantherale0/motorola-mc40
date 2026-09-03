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
import java.util.concurrent.TimeUnit

class NotifySocket(private val prefs: AppPrefs) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var socket: WebSocket? = null
    private var nextId = 2
    @Volatile private var generation = 0
    @Volatile private var wanted = false
    @Volatile private var subscribed = false
    private var backoffMs = MIN_BACKOFF_MS
    private val reconnect = Runnable {
        if (wanted && prefs.isRegistered) open()
    }

    @Synchronized
    fun connect() {
        wanted = true
        open()
    }

    @Synchronized
    fun ensureConnected() {
        if (!prefs.isRegistered || !wanted) return
        if (socket == null) open()
    }

    @Synchronized
    fun disconnect() {
        wanted = false
        subscribed = false
        generation++
        main.removeCallbacks(reconnect)
        closeSocket()
    }

    @Synchronized
    private fun open() {
        if (!prefs.isRegistered) return
        generation++
        val gen = generation
        closeSocket()
        subscribed = false
        nextId = 2
        val wsUrl = prefs.instanceUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://") + "/api/websocket"
        val request = Request.Builder().url(wsUrl).build()
        socket = HttpClients.webSocket.newWebSocket(request, Listener(gen))
        Log.i(Mc40App.TAG, "Notify websocket connecting $wsUrl")
    }

    private fun closeSocket() {
        socket?.close(1000, "bye")
        socket = null
    }

    private fun scheduleReconnect(reason: String) {
        if (!wanted || !prefs.isRegistered) return
        subscribed = false
        Log.w(Mc40App.TAG, "Notify websocket $reason; retry in ${backoffMs}ms")
        main.removeCallbacks(reconnect)
        main.postDelayed(reconnect, backoffMs)
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
    }

    private inner class Listener(private val gen: Int) : WebSocketListener() {
        private fun current(): Boolean = gen == generation && wanted

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!current()) return
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
                "auth_invalid" -> {
                    Log.w(Mc40App.TAG, "Notify websocket auth rejected: ${text.take(200)}")
                }
                "event" -> handleEvent(json)
                "result" -> handleResult(json, text)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!current()) return
            socket = null
            scheduleReconnect("closed $code $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!current()) return
            socket = null
            scheduleReconnect("failed: ${t.message}")
        }
    }

    private fun handleResult(json: JsonObject, text: String) {
        val id = json.get("id")?.asInt
        val success = json.get("success")?.asBoolean == true
        if (id == 1) {
            if (success) {
                subscribed = true
                backoffMs = MIN_BACKOFF_MS
                Log.i(Mc40App.TAG, "Notify websocket subscribed for local push")
            } else {
                subscribed = false
                Log.w(Mc40App.TAG, "Notify websocket subscribe failed: ${text.take(240)}")
                scheduleReconnect("subscribe failed")
            }
            return
        }
        if (!success) {
            Log.w(Mc40App.TAG, "WS result error: ${text.take(200)}")
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

    companion object {
        private val MIN_BACKOFF_MS = TimeUnit.SECONDS.toMillis(3)
        private val MAX_BACKOFF_MS = TimeUnit.SECONDS.toMillis(30)
    }
}
