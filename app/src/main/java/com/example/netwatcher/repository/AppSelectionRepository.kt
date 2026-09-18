package com.example.netwatcher.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.example.netwatcher.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppSelectionRepository(private val context: Context) {

    suspend fun getInstalledApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        val appList = mutableListOf<AppInfo>()

        for (appInfo in packages) {
            val appName = pm.getApplicationLabel(appInfo).toString()
            val packageName = appInfo.packageName

            // Exclude our own app from monitoring target list to prevent self-loop
            if (packageName == context.packageName) continue

            val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val icon = try {
                pm.getApplicationIcon(appInfo)
            } catch (_: Exception) {
                null
            }

            appList.add(
                AppInfo(
                    packageName = packageName,
                    appName = appName,
                    icon = icon,
                    isSystemApp = isSystemApp
                )
            )
        }

        // Sort by user apps first, then alphabetically by app name
        appList.sortedWith(compareBy({ it.isSystemApp }, { it.appName.lowercase() }))
    }
}
