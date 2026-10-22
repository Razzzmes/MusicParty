
package com.example.musicparty

import android.Manifest
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.musicparty.ui.theme.MusicPartyTheme
import kotlinx.coroutines.delay
import org.json.JSONObject

private const val WS_PORT = 8765
private const val WS_TAG = "MusicPartyWS"

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            if (permissions.values.all { it }) {
                Log.d("MusicParty", "Разрешения получены")
            } else {
                Log.e("MusicParty", "Не все разрешения получены")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestWifiPermissions()

        setContent {
            MusicPartyTheme {
                val navController = rememberNavController()

                NavHost(
                    navController = navController,
                    startDestination = "main"
                ) {
                    composable("main") {
                        MusicPartyScreen(
                            onCreateRoom = {
                                navController.navigate("room")
                            },
                            onFindRoom = {
                                navController.navigate("search")
                            }
                        )
                    }

                    composable("room") {
                        RoomScreen(
                            onBack = {
                                navController.popBackStack()
                            }
                        )
                    }

                    composable("search") {
                        SearchRoomScreen(
                            onBack = {
                                navController.popBackStack()
                            }
                        )
                    }
                }
            }
        }
    }

    private fun requestWifiPermissions() {
        val permissions =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            }

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(
                this,
                it
            ) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(
                permissionsToRequest.toTypedArray()
            )
        }
    }
}

@Composable
fun MusicPartyScreen(
    onCreateRoom: () -> Unit,
    onFindRoom: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Music Party",
            style = MaterialTheme.typography.headlineLarge
        )

        Text(
            text = "Совместное прослушивание музыки\nпо локальной сети",
            modifier = Modifier.padding(top = 12.dp)
        )

        Button(
            onClick = onCreateRoom,
            modifier = Modifier.padding(top = 32.dp)
        ) {
            Text("Создать комнату")
        }

        Button(
            onClick = onFindRoom,
            modifier = Modifier.padding(top = 12.dp)
        ) {
            Text("Найти комнату")
        }
    }
}

@Composable
fun RoomScreen(onBack: () -> Unit) {
    ConnectionScreen(
        title = "Создание комнаты",
        onBack = onBack
    )
}

@Composable
fun SearchRoomScreen(onBack: () -> Unit) {
    ConnectionScreen(
        title = "Поиск комнаты",
        onBack = onBack
    )
}

@Composable
private fun ConnectionScreen(
    title: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    val mainHandler = remember {
        Handler(Looper.getMainLooper())
    }

    val devices = remember {
        mutableStateListOf<WifiP2pDevice>()
    }

    val connectionInfo = remember {
        mutableStateOf<WifiP2pInfo?>(null)
    }

    val wsStatus = remember {
        mutableStateOf("Ожидание Wi-Fi Direct")
    }

    val wsMessages = remember {
        mutableStateListOf<String>()
    }

    val chatText = remember {
        mutableStateOf("")
    }

    val localTestRequested = remember {
        mutableStateOf(false)
    }

    val wsServer = remember {
        mutableStateOf<MusicPartyWebSocketServer?>(null)
    }

    val wsClient = remember {
        mutableStateOf<MusicPartyWebSocketClient?>(null)
    }

    val wifiDirectManager = remember {
        WifiDirectManager(
            context = context.applicationContext,

            onDevicesChanged = { newDevices ->
                mainHandler.post {
                    devices.clear()
                    devices.addAll(newDevices)
                }
            },

            onConnectionChanged = { info ->
                mainHandler.post {
                    connectionInfo.value = info
                }
            }
        )
    }

    DisposableEffect(Unit) {
        wifiDirectManager.start()

        onDispose {
            wifiDirectManager.stop()

            wsClient.value?.disconnect()
            wsClient.value = null

            wsServer.value?.stop()
            wsServer.value = null
        }
    }

    /*
     * Локальный тест WebSocket.
     *
     * Сервер и клиент работают в одном эмуляторе.
     * Адрес 127.0.0.1 указывает на сам эмулятор.
     */
    LaunchedEffect(localTestRequested.value) {
        if (!localTestRequested.value) {
            return@LaunchedEffect
        }

        if (wsServer.value != null || wsClient.value != null) {
            wsMessages.add(
                "Сначала останови существующее WebSocket-соединение"
            )
            localTestRequested.value = false
            return@LaunchedEffect
        }

        wsStatus.value = "Запуск локального теста..."

        wsMessages.add("Запускаем WebSocket-сервер...")

        val server = MusicPartyWebSocketServer(
            port = WS_PORT,
            onEvent = { event ->
                Log.d(WS_TAG, "[SERVER] $event")

                mainHandler.post {
                    wsMessages.add("[SERVER] $event")
                }
            }
        )

        wsServer.value = server
        server.start()

        // Ждём запуска сервера перед подключением клиента.
        delay(1000)

        val client = MusicPartyWebSocketClient(
            serverUrl = "ws://127.0.0.1:$WS_PORT",
            participantName = "EmulatorTest",

            onStateChanged = { state ->
                Log.d(WS_TAG, "[CLIENT] $state")

                mainHandler.post {
                    wsStatus.value = state
                    wsMessages.add("[CLIENT] $state")
                }
            },

            onMessageReceived = { message ->
                Log.d(WS_TAG, "[CLIENT] Получено: $message")

                val displayMessage = try {
                    val json = JSONObject(message)

                    when (json.optString("type")) {
                        "chat" ->
                            "${json.optString("name")}: " +
                                    json.optString("text")

                        "system" ->
                            json.optString("text")

                        "participants" ->
                            "Участники: " +
                                    json.optJSONArray("names").toString()

                        "command" ->
                            "Команда: " +
                                    json.optString("action")

                        else -> message
                    }
                } catch (_: Exception) {
                    message
                }

                mainHandler.post {
                    wsMessages.add("[CLIENT] $displayMessage")
                }
            }
        )

        wsClient.value = client
        client.connect()

        localTestRequested.value = false
    }

    /*
     * Основная логика Wi-Fi Direct.
     *
     * Когда группа сформирована:
     * - владелец запускает WebSocket-сервер;
     * - участник подключается к серверу владельца.
     */
    val info = connectionInfo.value
    val ownerIp = info?.groupOwnerAddress?.hostAddress

    LaunchedEffect(
        info?.groupFormed,
        info?.isGroupOwner,
        ownerIp
    ) {
        // Во время локального теста Wi-Fi Direct не управляет WebSocket.
        if (wsServer.value != null || wsClient.value != null) {
            return@LaunchedEffect
        }

        if (info?.groupFormed != true || ownerIp.isNullOrBlank()) {
            wsStatus.value = "Ожидание Wi-Fi Direct-соединения"
            return@LaunchedEffect
        }

        if (info.isGroupOwner) {
            wsStatus.value = "Запуск WebSocket-сервера..."

            val server = MusicPartyWebSocketServer(
                port = WS_PORT,
                onEvent = { event ->
                    Log.d(WS_TAG, "[SERVER] $event")

                    mainHandler.post {
                        wsStatus.value = event
                        wsMessages.add("[SERVER] $event")
                    }
                }
            )

            wsServer.value = server
            server.start()
        } else {
            wsStatus.value = "Подключение к серверу владельца..."

            delay(1500)

            val client = MusicPartyWebSocketClient(
                serverUrl = "ws://$ownerIp:$WS_PORT",
                participantName = Build.MODEL,

                onStateChanged = { state ->
                    Log.d(WS_TAG, "[CLIENT] $state")

                    mainHandler.post {
                        wsStatus.value = state
                        wsMessages.add("[CLIENT] $state")
                    }
                },

                onMessageReceived = { message ->
                    Log.d(WS_TAG, "[CLIENT] Получено: $message")

                    mainHandler.post {
                        wsMessages.add("[CLIENT] Получено: $message")
                    }
                }
            )

            wsClient.value = client
            client.connect()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(Modifier.height(12.dp))

        if (info?.groupFormed == true) {
            Text("Wi-Fi Direct: подключено")

            Text(
                if (info.isGroupOwner) {
                    "Роль: владелец группы"
                } else {
                    "Роль: участник"
                }
            )

            Text("IP владельца: $ownerIp")
        } else {
            Text(
                if (devices.isEmpty()) {
                    "Поиск устройств Wi-Fi Direct..."
                } else {
                    "Найдено устройств: ${devices.size}"
                }
            )
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = "WebSocket: ${wsStatus.value}",
            style = MaterialTheme.typography.titleMedium
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
        ) {
            items(
                items = devices,
                key = { it.deviceAddress }
            ) { device ->
                DeviceItem(
                    device = device,
                    onConnect = {
                        wifiDirectManager.connect(device)
                    }
                )
            }
        }

        // Кнопка тестирования сервера и клиента на одном эмуляторе.
        Button(
            onClick = {
                localTestRequested.value = true
            },
            enabled = wsServer.value == null &&
                    wsClient.value == null &&
                    !localTestRequested.value,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text("Тест WebSocket локально")
        }

        if (info?.groupFormed == true && !info.isGroupOwner) {
            OutlinedTextField(
                value = chatText.value,
                onValueChange = { chatText.value = it },
                label = { Text("Тестовое сообщение") },
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    val text = chatText.value.trim()

                    if (text.isNotEmpty()) {
                        wsClient.value?.sendChat(text)
                        chatText.value = ""
                    }
                },
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text("Отправить")
            }

            Button(
                onClick = {
                    wsClient.value?.sendCommand("play", 0L)
                },
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text("Отправить команду Play")
            }
        }

        Text(
            text = "События и сообщения:",
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            style = MaterialTheme.typography.titleMedium
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            items(wsMessages.toList()) { message ->
                Text(
                    text = message,
                    modifier = Modifier.padding(vertical = 3.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Button(
            onClick = onBack,
            modifier = Modifier.padding(top = 12.dp)
        ) {
            Text("← Назад")
        }
    }
}

@Composable
fun DeviceItem(
    device: WifiP2pDevice,
    onConnect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = device.deviceName.ifBlank {
                        "Неизвестное устройство"
                    },
                    style = MaterialTheme.typography.titleMedium
                )

                Text(
                    text = device.deviceAddress,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Button(onClick = onConnect) {
                Text("Подключиться")
            }
        }
    }
}
