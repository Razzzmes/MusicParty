package com.example.musicparty

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class MusicPartyWebSocketServer(
    private val port: Int = 8765,
    private val onEvent: (String) -> Unit
) {

    private val participants =
        ConcurrentHashMap<WebSocket, String>()

    private val startRequested = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)

    private val server = object : WebSocketServer(
        InetSocketAddress(port)
    ) {

        init {
            // Разрешаем повторное использование адреса,
            // когда ОС допускает это после закрытия сокета.
            setReuseAddr(true)
        }

        override fun onStart() {
            onEvent("WebSocket-сервер запущен на порту $port")
        }

        override fun onOpen(
            conn: WebSocket,
            handshake: ClientHandshake
        ) {
            onEvent("Новое WebSocket-подключение")
        }

        override fun onMessage(
            conn: WebSocket,
            message: String
        ) {
            try {
                val json = JSONObject(message)

                when (json.optString("type")) {

                    "join" -> {
                        val name = json.optString(
                            "name",
                            "Участник"
                        ).ifBlank {
                            "Участник"
                        }

                        participants[conn] = name

                        onEvent("$name подключился к комнате")

                        broadcast(
                            JSONObject()
                                .put("type", "system")
                                .put(
                                    "text",
                                    "$name вошёл в комнату"
                                )
                        )

                        sendParticipants()
                    }

                    "chat" -> {
                        val name = participants[conn]
                            ?: "Неизвестный участник"

                        val text = json.optString("text")

                        broadcast(
                            JSONObject()
                                .put("type", "chat")
                                .put("name", name)
                                .put("text", text)
                        )
                    }

                    "command" -> {
                        val action = json.optString("action")
                        val positionMs = json.optLong(
                            "positionMs",
                            0L
                        )

                        broadcast(
                            JSONObject()
                                .put("type", "command")
                                .put("action", action)
                                .put("positionMs", positionMs)
                        )

                        onEvent("Получена команда: $action")
                    }

                    else -> {
                        sendTo(
                            conn,
                            JSONObject()
                                .put("type", "error")
                                .put(
                                    "text",
                                    "Неизвестный тип сообщения"
                                )
                        )
                    }
                }
            } catch (e: Exception) {
                onEvent(
                    "Ошибка обработки сообщения: ${e.message}"
                )
            }
        }

        override fun onClose(
            conn: WebSocket,
            code: Int,
            reason: String,
            remote: Boolean
        ) {
            val name = participants.remove(conn)

            if (name != null) {
                onEvent("$name отключился")

                broadcast(
                    JSONObject()
                        .put("type", "system")
                        .put("text", "$name покинул комнату")
                )

                sendParticipants()
            } else {
                onEvent("WebSocket-соединение закрыто")
            }
        }

        override fun onError(
            conn: WebSocket?,
            ex: Exception
        ) {
            onEvent(
                "Ошибка WebSocket: ${ex.message ?: ex.javaClass.simpleName}"
            )
        }

        private fun broadcast(json: JSONObject) {
            val message = json.toString()

            participants.keys.forEach { connection ->
                try {
                    if (connection.isOpen) {
                        connection.send(message)
                    }
                } catch (e: Exception) {
                    onEvent(
                        "Не удалось отправить сообщение: ${e.message}"
                    )
                }
            }
        }

        private fun sendParticipants() {
            val json = JSONObject()
                .put("type", "participants")
                .put(
                    "names",
                    JSONArray(participants.values.toList())
                )

            broadcast(json)
        }

        private fun sendTo(
            connection: WebSocket,
            json: JSONObject
        ) {
            try {
                if (connection.isOpen) {
                    connection.send(json.toString())
                }
            } catch (e: Exception) {
                onEvent(
                    "Ошибка отправки сообщения: ${e.message}"
                )
            }
        }
    }

    /**
     * Запрашивает запуск сервера только один раз
     * для данного экземпляра.
     *
     * Фактический успешный запуск подтверждается
     * событием onStart().
     */
    @Synchronized
    fun start() {
        if (!startRequested.compareAndSet(false, true)) {
            onEvent("Запуск сервера уже запрашивался")
            return
        }

        stopRequested.set(false)

        try {
            server.start()
        } catch (e: Exception) {
            startRequested.set(false)

            onEvent(
                "Не удалось запустить сервер: ${e.message}"
            )
        }
    }

    /**
     * Останавливает сервер и закрывает соединения.
     */
    @Synchronized
    fun stop() {
        if (!startRequested.get()) {
            return
        }

        if (!stopRequested.compareAndSet(false, true)) {
            return
        }

        try {
            server.stop(5000)

            participants.clear()

            onEvent("WebSocket-сервер остановлен")
        } catch (e: Exception) {
            onEvent(
                "Ошибка остановки сервера: ${e.message}"
            )
        } finally {
            startRequested.set(false)
        }
    }
}