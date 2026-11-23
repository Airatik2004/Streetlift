package ru.streetlift.tracker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import ru.streetlift.tracker.ui.AppRoot
import ru.streetlift.tracker.ui.theme.StreetLiftTheme

class MainActivity : ComponentActivity() {

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleOpenIntent(intent)
    }

    /** Из уведомления: открыть экран идущей тренировки. */
    private fun handleOpenIntent(intent: android.content.Intent?) {
        val id = intent?.getLongExtra(NavRequests.EXTRA_OPEN_SESSION, -1L) ?: -1L
        if (id > 0) {
            NavRequests.openWorkout.value = id
            intent?.removeExtra(NavRequests.EXTRA_OPEN_SESSION)
        }
        val tab = intent?.getIntExtra(NavRequests.EXTRA_OPEN_TAB, -1) ?: -1
        if (tab >= 0) {
            NavRequests.openTab.value = tab
            intent?.removeExtra(NavRequests.EXTRA_OPEN_TAB)
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handleOpenIntent(intent)
        val repo = (application as StreetLiftApp).repository
        setContent {
            StreetLiftTheme {
                AppRoot(repo)
            }
        }
    }
}
