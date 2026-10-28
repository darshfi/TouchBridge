package dev.touchbridge.nearby

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object NearbyPermissions {
    fun required(api: Int): List<String> = buildList {
        if (api <= 32) {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (api >= 29) add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (api >= 31) {
            add("android.permission.BLUETOOTH_SCAN")
            add("android.permission.BLUETOOTH_CONNECT")
            add("android.permission.BLUETOOTH_ADVERTISE")
        }
        if (api >= 33) add("android.permission.NEARBY_WIFI_DEVICES")
        if (api >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
    }
    fun missing(context: Context) = required(Build.VERSION.SDK_INT)
        .filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
}
