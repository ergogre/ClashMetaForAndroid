package com.github.kr328.clash.service

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.*
import com.github.kr328.clash.core.event.*
import com.github.kr328.clash.core.utils.Log
import com.github.kr328.clash.service.net.DefaultNetworkObserver

class TunService : VpnService(), IClashEventObserver {
    companion object {
        // from https://github.com/shadowsocks/shadowsocks-android/blob/master/core/src/main/java/com/github/shadowsocks/bg/VpnService.kt
        private const val VPN_MTU = 1500
        private const val PRIVATE_VLAN4_SUBNET = 30
        private const val PRIVATE_VLAN4_CLIENT = "172.31.255.253"
        private const val PRIVATE_VLAN_DNS = "172.31.255.254"
        private const val VLAN4_ANY = "0.0.0.0"
    }

    private var start = true
    private lateinit var fileDescriptor: ParcelFileDescriptor
    private lateinit var clash: ClashServiceImpl
    private lateinit var defaultNetworkObserver: DefaultNetworkObserver
    private lateinit var settings: ClashSettingService
    private val connection = object : ServiceConnection {
        override fun onServiceDisconnected(name: ComponentName?) {
            stopSelf()
        }

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val clash = IClashService.Stub.asInterface(
                service
            ) ?: throw NullPointerException()

            this@TunService.clash = (clash as ClashServiceImpl)

            start = true

            clash.eventService.registerEventObserver(
                TunService::class.java.simpleName,
                this@TunService,
                intArrayOf()
            )
        }
    }

    override fun onCreate() {
        super.onCreate()

        if (prepare(this) != null) {
            stopSelf()
            return
        }

        settings = ClashSettingService(this)

        fileDescriptor = Builder()
            .addAddress()
            .addDnsServer(PRIVATE_VLAN_DNS)
            .addBypassApplications()
            .addBypassPrivateRoute()
            .setMtu(VPN_MTU)
            .setBlocking(false)
            .setMeteredCompat(false)
            .establish() ?: throw NullPointerException("Unable to establish VPN")

        bindService(Intent(this, ClashService::class.java), connection, Context.BIND_AUTO_CREATE)

        defaultNetworkObserver = DefaultNetworkObserver(this) {
            setUnderlyingNetworks(it?.run { arrayOf(it) })
        }

        defaultNetworkObserver.register()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        return Service.START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        clash.stop()

        clash.eventService.unregisterEventObserver(TunService::class.java.simpleName)

        unbindService(connection)

        defaultNetworkObserver.unregister()
    }

    override fun onProcessEvent(event: ProcessEvent?) {
        when (event) {
            ProcessEvent.STOPPED -> {
                val startNow = start
                start = false

                if (startNow)
                    clash.start()
                else
                    stopSelf()

                clash.clash.stopTunDevice()

                Log.i("STOPPED")
            }
            ProcessEvent.STARTED -> {
                start = false

                if ( settings.isDnsHijackingEnabled ) {
                    clash.clash.startTunDevice(
                        fileDescriptor.fd, VPN_MTU,
                        VLAN4_ANY
                    )
                }
                else {
                    clash.clash.startTunDevice(
                        fileDescriptor.fd, VPN_MTU,
                        PRIVATE_VLAN_DNS
                    )
                }

                fileDescriptor.close()

                Log.i("STARTED")
            }
        }
    }

    private fun Builder.setMeteredCompat(isMetered: Boolean): Builder {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            setMetered(isMetered)
        return this
    }

    private fun Builder.addBypassPrivateRoute(): Builder {
        // IPv4
        if ( settings.isBypassPrivateNetwork ) {
            Log.i("Bypass Private Network")

            resources.getStringArray(R.array.bypass_private_route).forEach {
                val address = it.split("/")
                addRoute(address[0], address[1].toInt())
            }
        }
        else {
            addRoute("0.0.0.0", 0)
        }

        return this
    }

    private fun Builder.addBypassApplications(): Builder {
        when ( settings.accessControlMode ) {
            ClashSettingService.ACCESS_CONTROL_MODE_ALLOW_ALL -> {
                for ( app in resources.getStringArray(R.array.default_disallow_application) ) {
                    runCatching {
                        addDisallowedApplication(app)
                    }
                }
                addDisallowedApplication(packageName)
            }
            ClashSettingService.ACCESS_CONTROL_MODE_ALLOW -> {
                addAllowedApplication(packageName)
                for ( app in settings.accessControlApps.toSet() -
                        resources.getStringArray(R.array.default_disallow_application) -
                        setOf(packageName) ) {
                    runCatching {
                        addAllowedApplication(app)
                    }.onFailure {
                        Log.w("Package $app not found")
                    }
                }
            }
            ClashSettingService.ACCESS_CONTROL_MODE_DISALLOW -> {
                for ( app in settings.accessControlApps.toSet() +
                        resources.getStringArray(R.array.default_disallow_application) ) {
                    runCatching {
                        addDisallowedApplication(app)
                    }.onFailure {
                        Log.w("Package $app not found")
                    }
                }
                addDisallowedApplication(packageName)
            }
        }

        return this
    }

    private fun Builder.addAddress(): Builder {
        addAddress(PRIVATE_VLAN4_CLIENT, PRIVATE_VLAN4_SUBNET)

        return this
    }

    override fun onTrafficEvent(event: TrafficEvent?) {}
    override fun onBandwidthEvent(event: BandwidthEvent?) {}
    override fun onLogEvent(event: LogEvent?) {}
    override fun onErrorEvent(event: ErrorEvent?) {}
    override fun onProfileChanged(event: ProfileChangedEvent?) {}
    override fun onProfileReloaded(event: ProfileReloadEvent?) {}
    override fun asBinder(): IBinder = object : Binder() {
        override fun queryLocalInterface(descriptor: String): IInterface? {
            return this@TunService
        }
    }
}