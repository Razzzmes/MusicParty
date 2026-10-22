package com.example.musicparty

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI

class MusicPartyWebSocketClient(
    private val serverUrl: String,
    private val participantName: String,
    private val onStateChanged: (String) -> Unit,
    private val onMessageReceived: (String) -> Unit
) {
    private var client: WebSocketClient? = null

    fun connect() {
        if (client?.isOpen == true) {
            onStateChanged("Уже подключено")
            return
        }

        try {
            val uri = URI(serverUrl)

            val newClient = object : WebSocketClient(uri) {
                override fun onOpen(handshakedata: ServerHandshake) {
                    onStateChanged("Подключено к комнате")

                    send(
                        JSONObject()
                            .put("type", "join")
                            .put("name", participantName)
                            .toString()
                    )
                }

                override fun onMessage(message: String) {
                    onMessageReceived(message)
                }

                override fun onClose(
                    code: Int,
                    reason: String,
                    remote: Boolean
                ) {
                    onStateChanged("Соединение закрыто: $reason")
                }

                override fun onError(ex: Exception) {
                    onStateChanged(
                        "Ошибка соединения: ${ex.message}"
                    )
                }
            }

            client = newClient
            newClient.connect()
            onStateChanged("Подключение...")
        } catch (e: Exception) {
            onStateChanged("Не удалось создать подключение: ${e.message}")
        }
    }

    fun sendChat(text: String) {
        sendJson(
            JSONObject()
                .put("type", "chat")
                .put("text", text)
        )
    }

    fun sendCommand(
        action: String,
        positionMs: Long = 0L
    ) {
        sendJson(
            JSONObject()
                .put("type", "command")
                .put("action", action)
                .put("positionMs", positionMs)
        )
    }

    private fun sendJson(json: JSONObject) {
        val currentClient = client

        if (currentClient?.isOpen == true) {
            currentClient.send(json.toString())
        } else {
            onStateChanged("Нет соединения с комнатой")
        }
    }

    fun disconnect() {
        client?.close()
        client = null
        onStateChanged("Отключение...")
    }
}