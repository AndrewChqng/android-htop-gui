package com.andrewchqng.htop.shizuku

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object ShizukuHelper {

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    fun isRunning(): Boolean = try {
        Shizuku.getBinder() != null && Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun isPermissionGranted(): Boolean = try {
        isRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun serverUid(): Int = try {
        if (isRunning()) Shizuku.getUid() else -1
    } catch (_: Throwable) {
        -1
    }

    fun requestPermission(requestCode: Int): Boolean = try {
        if (!isRunning()) {
            false
        } else if (isPermissionGranted()) {
            true
        } else {
            Shizuku.requestPermission(requestCode)
            true
        }
    } catch (_: Throwable) {
        false
    }
}
