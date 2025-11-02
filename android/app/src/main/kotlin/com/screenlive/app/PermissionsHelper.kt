package com.screenlive.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Helper class to handle all runtime permissions for livestreaming
 */
class PermissionsHelper(private val activity: Activity) {
    
    companion object {
        const val REQUEST_CODE_ALL_PERMISSIONS = 1000
        
        // All permissions needed for livestreaming
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_NETWORK_STATE
        ).apply {
            // Android 13+ requires POST_NOTIFICATIONS for foreground service notification
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            // Android 14+ (API 34) requires FOREGROUND_SERVICE_MEDIA_PROJECTION
            // Thay "UPSIDE_DOWN_CAKE" bằng giá trị số vì constant chưa có
            if (Build.VERSION.SDK_INT >= 34) {
                add("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION")
            }
        }.toTypedArray()
    }
    
    /**
     * Check if all required permissions are granted
     */
    fun hasAllPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Get list of permissions that are not yet granted
     */
    fun getMissingPermissions(): List<String> {
        return REQUIRED_PERMISSIONS.filter { permission ->
            ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Request all required permissions at once
     */
    fun requestAllPermissions() {
        val missing = getMissingPermissions()
        if (missing.isNotEmpty()) {
            android.util.Log.d("PermissionsHelper", "Requesting permissions: ${missing.joinToString()}")
            ActivityCompat.requestPermissions(
                activity,
                missing.toTypedArray(),
                REQUEST_CODE_ALL_PERMISSIONS
            )
        } else {
            android.util.Log.d("PermissionsHelper", "All permissions already granted")
        }
    }
    
    /**
     * Check specific permission
     */
    fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Get permission status details for debugging
     */
    fun getPermissionStatus(): Map<String, Boolean> {
        return REQUIRED_PERMISSIONS.associate { permission ->
            permission to hasPermission(permission)
        }
    }
}
