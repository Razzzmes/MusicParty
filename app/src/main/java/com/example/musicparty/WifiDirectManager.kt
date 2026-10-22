package com.example.musicparty

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

class WifiDirectManager(
    private val context: Context,
    private val onDevicesChanged: (List<WifiP2pDevice>) -> Unit,
    private val onConnectionChanged: (WifiP2pInfo?) -> Unit
) {

    private val wifiP2pManager: WifiP2pManager =
        context.getSystemService(Context.WIFI_P2P_SERVICE)
                as WifiP2pManager

    private val channel: WifiP2pManager.Channel =
        wifiP2pManager.initialize(
            context,
            context.mainLooper,
            null
        )

    private val intentFilter = IntentFilter().apply {

        addAction(
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION
        )

        addAction(
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION
        )

        addAction(
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION
        )

        addAction(
            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION
        )
    }

    private val receiver = object : BroadcastReceiver() {

        override fun onReceive(
            context: Context?,
            intent: Intent?
        ) {

            when (intent?.action) {

                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {

                    val state = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE,
                        -1
                    )

                    if (
                        state ==
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    ) {

                        Log.d(
                            "MusicParty",
                            "Wi-Fi Direct включён"
                        )

                    } else {

                        Log.d(
                            "MusicParty",
                            "Wi-Fi Direct выключен"
                        )
                    }
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {

                    Log.d(
                        "MusicParty",
                        "Изменился список устройств"
                    )

                    requestPeers()
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {

                    Log.d(
                        "MusicParty",
                        "Изменилось состояние подключения"
                    )

                    requestConnectionInfo()
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {

                    Log.d(
                        "MusicParty",
                        "Изменилось состояние этого устройства"
                    )
                }
            }
        }
    }

    fun start() {

        registerReceiver()

        discoverPeers()
    }

    fun stop() {

        try {

            context.unregisterReceiver(receiver)

        } catch (e: IllegalArgumentException) {

            Log.d(
                "MusicParty",
                "Receiver уже был отключён"
            )
        }
    }

    private fun registerReceiver() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            ContextCompat.registerReceiver(
                context,
                receiver,
                intentFilter,
                ContextCompat.RECEIVER_EXPORTED
            )

        } else {

            @Suppress("DEPRECATION")
            context.registerReceiver(
                receiver,
                intentFilter
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverPeers() {

        if (!hasWifiPermission()) {

            Log.d(
                "MusicParty",
                "Нет разрешения на работу с Wi-Fi"
            )

            return
        }

        wifiP2pManager.discoverPeers(
            channel,

            object : WifiP2pManager.ActionListener {

                override fun onSuccess() {

                    Log.d(
                        "MusicParty",
                        "Поиск устройств запущен"
                    )
                }

                override fun onFailure(
                    reason: Int
                ) {

                    Log.d(
                        "MusicParty",
                        "Ошибка поиска устройств: $reason"
                    )
                }
            }
        )
    }

    @SuppressLint("MissingPermission")
    private fun requestPeers() {

        if (!hasWifiPermission()) {

            Log.d(
                "MusicParty",
                "Нет разрешения на получение списка устройств"
            )

            return
        }

        wifiP2pManager.requestPeers(
            channel
        ) { peers: WifiP2pDeviceList ->

            val devices = peers.deviceList.toList()

            Log.d(
                "MusicParty",
                "Найдено устройств: ${devices.size}"
            )

            for (device in devices) {

                Log.d(
                    "MusicParty",
                    "Устройство: " +
                            "${device.deviceName} " +
                            "MAC: ${device.deviceAddress}"
                )
            }

            onDevicesChanged(devices)
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: WifiP2pDevice) {

        if (!hasWifiPermission()) {

            Log.d(
                "MusicParty",
                "Нет разрешения на подключение"
            )

            return
        }

        val config = WifiP2pConfig().apply {

            deviceAddress = device.deviceAddress
        }

        Log.d(
            "MusicParty",
            "Подключаемся к: ${device.deviceName}"
        )

        wifiP2pManager.connect(
            channel,
            config,

            object : WifiP2pManager.ActionListener {

                override fun onSuccess() {

                    Log.d(
                        "MusicParty",
                        "Запрос на подключение отправлен"
                    )
                }

                override fun onFailure(
                    reason: Int
                ) {

                    Log.d(
                        "MusicParty",
                        "Ошибка подключения: $reason"
                    )
                }
            }
        )
    }

    @SuppressLint("MissingPermission")
    private fun requestConnectionInfo() {

        if (!hasWifiPermission()) {

            return
        }

        wifiP2pManager.requestConnectionInfo(
            channel
        ) { info: WifiP2pInfo ->

            if (info.groupFormed) {

                Log.d(
                    "MusicParty",
                    "Wi-Fi Direct соединение установлено"
                )

                Log.d(
                    "MusicParty",
                    "Является владельцем группы: " +
                            info.isGroupOwner
                )

                Log.d(
                    "MusicParty",
                    "IP владельца группы: " +
                            info.groupOwnerAddress?.hostAddress
                )

                onConnectionChanged(info)

            } else {

                Log.d(
                    "MusicParty",
                    "Wi-Fi Direct соединение отсутствует"
                )

                onConnectionChanged(null)
            }
        }
    }

    private fun hasWifiPermission(): Boolean {

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED

        } else {

            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}