package com.aldiianshr.iphonemiror

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aldiianshr.iphonemiror.connection.ReceiverController
import com.aldiianshr.iphonemiror.core.AppLog
import com.aldiianshr.iphonemiror.discovery.UsbAppleMonitor
import com.aldiianshr.iphonemiror.domain.model.ReceiverStatus
import com.aldiianshr.iphonemiror.ui.AppRoot
import com.aldiianshr.iphonemiror.ui.AppScreen
import com.aldiianshr.iphonemiror.ui.theme.IPhoneMirorTheme
import com.aldiianshr.iphonemiror.ui.viewmodel.ReceiverViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val receiverVm: ReceiverViewModel by viewModels()
    @Inject lateinit var usbMonitor: UsbAppleMonitor

    /** Screen requested from outside (USB attach, auto-open on stream start, start_route extra). */
    private val requestedScreen = MutableStateFlow<AppScreen?>(null)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                receiverVm.events.collect { event ->
                    if (event is ReceiverController.Event.MirrorStarted && receiverVm.settings.value.autoOpenMirroring) {
                        requestedScreen.value = AppScreen.MIRRORING
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                receiverVm.state.collect { s ->
                    val keepOn = s.status == ReceiverStatus.MIRRORING && receiverVm.settings.value.keepScreenOn
                    if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        setContent {
            IPhoneMirorTheme {
                var screen by rememberSaveable { mutableStateOf(AppScreen.DASHBOARD) }
                var fullscreen by rememberSaveable { mutableStateOf(false) }
                val requested by requestedScreen.collectAsStateWithLifecycle()
                LaunchedEffect(requested) {
                    requested?.let { screen = it; requestedScreen.value = null }
                }
                LaunchedEffect(fullscreen, screen) { applyFullscreen(fullscreen && screen == AppScreen.MIRRORING) }
                AppRoot(
                    screen = screen,
                    onNavigate = { screen = it; if (it != AppScreen.MIRRORING) fullscreen = false },
                    fullscreen = fullscreen,
                    onFullscreen = { fullscreen = it },
                    receiverVm = receiverVm,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            AppLog.i("MainActivity", "Opened by USB attach")
            usbMonitor.start()
            usbMonitor.refresh()
            requestedScreen.value = AppScreen.CONNECT
        }
        // Debug-build hooks used by the automated emulator job (screenshots / smoke test).
        if (BuildConfig.DEBUG) {
            if (intent.getBooleanExtra(EXTRA_START_RECEIVER, false)) receiverVm.startReceiver()
            if (intent.getBooleanExtra(EXTRA_RUN_SELF_TEST, false)) {
                lifecycleScope.launch { kotlinx.coroutines.delay(2_500); receiverVm.runSelfTest() }
            }
        }
        intent.getStringExtra(EXTRA_START_ROUTE)?.let { route ->
            AppScreen.entries.firstOrNull { it.name.equals(route, ignoreCase = true) }?.let { requestedScreen.value = it }
        }
    }

    private fun applyFullscreen(enabled: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    companion object {
        /** Opens a given page (used by the automated screenshot job and by notifications). */
        const val EXTRA_START_ROUTE = "start_route"
        const val EXTRA_START_RECEIVER = "start_receiver"
        const val EXTRA_RUN_SELF_TEST = "run_self_test"
    }
}
