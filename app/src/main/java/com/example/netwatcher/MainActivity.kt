package com.example.netwatcher

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.example.netwatcher.model.AppInfo
import com.example.netwatcher.repository.AppSelectionRepository
import com.example.netwatcher.theme.NetWatcherTheme
import com.example.netwatcher.ui.MainScreen
import com.example.netwatcher.vpn.NetWatcherVpnService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var pendingTargetPackage: String? = null
    private var pendingTargetAppName: String = "All Applications"

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnServiceInternal(pendingTargetPackage, pendingTargetAppName)
        } else {
            Toast.makeText(this, "VPN permission denied by user", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appRepository = AppSelectionRepository(this)

        setContent {
            NetWatcherTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }

                    LaunchedEffect(Unit) {
                        lifecycleScope.launch {
                            installedApps = appRepository.getInstalledApps()
                        }
                    }

                    MainScreen(
                        installedApps = installedApps,
                        onRequestVpnStart = { targetPkg, appName ->
                            requestVpnStart(targetPkg, appName)
                        },
                        onRequestVpnStop = {
                            requestVpnStop()
                        }
                    )
                }
            }
        }
    }

    private fun requestVpnStart(targetPkg: String?, appName: String) {
        pendingTargetPackage = targetPkg
        pendingTargetAppName = appName

        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            // Already authorized
            startVpnServiceInternal(targetPkg, appName)
        }
    }

    private fun startVpnServiceInternal(targetPkg: String?, appName: String) {
        val serviceIntent = Intent(this, NetWatcherVpnService::class.java).apply {
            action = NetWatcherVpnService.ACTION_START
            putExtra(NetWatcherVpnService.EXTRA_TARGET_PACKAGE, targetPkg)
            putExtra(NetWatcherVpnService.EXTRA_TARGET_APP_NAME, appName)
        }
        startService(serviceIntent)
    }

    private fun requestVpnStop() {
        val serviceIntent = Intent(this, NetWatcherVpnService::class.java).apply {
            action = NetWatcherVpnService.ACTION_STOP
        }
        startService(serviceIntent)
    }
}
