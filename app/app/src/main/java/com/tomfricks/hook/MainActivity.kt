package com.tomfricks.hook

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.tomfricks.hook.billing.BillingManager
import com.tomfricks.hook.data.UserPreferences
import com.tomfricks.hook.ui.navigation.HookNavigation
import com.tomfricks.hook.ui.theme.HookTheme
import com.tomfricks.hook.utils.PermissionUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /**
     * Bumped every time something asks for the paywall. A counter rather than a
     * flag so a second request while the app is already open still navigates.
     */
    private var paywallRequest by mutableStateOf(0)

    /**
     * At most one POST_NOTIFICATIONS re-prompt per launch, so a resume loop can't
     * turn into a dialog loop. See [maybeRequestNotificationPermission].
     */
    private var notificationPermissionRequested = false

    /**
     * The result lands on the system notification toggle we already read through
     * [PermissionUtils.hasNotificationAccess], so there is nothing to do here —
     * registering the launcher is what lets us show the dialog at all.
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handlePaywallIntent(intent)

        setContent {
            val app = application as HookApplication
            val userPreferences by app.preferencesRepository.userPreferencesFlow.collectAsState(
                initial = UserPreferences()
            )

            HookTheme(themeMode = userPreferences.themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HookNavigation(paywallRequest = paywallRequest)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The keyboard launches us with FLAG_ACTIVITY_NEW_TASK while the app is
        // often already alive; singleTop routes that here instead of onCreate.
        setIntent(intent)
        handlePaywallIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // A subscription can be bought, cancelled or refunded outside the app.
        BillingManager.refreshInBackground()
        maybeRequestNotificationPermission()
    }

    /**
     * Close the upgrade gap: people who finished onboarding on an older build were
     * never asked for POST_NOTIFICATIONS, so on Android 13+ (targetSdk 35) Android
     * silently withholds the screenshot service's ongoing notification. That leaves
     * a FOREGROUND_SERVICE_DATA_SYNC running with nothing on screen — a Play-policy
     * violation. Re-ask once per launch for anyone already onboarded but not yet
     * granted; the once-per-session flag keeps a resume loop from spamming the
     * dialog, and a permanently denied user just gets an instant no-op.
     */
    private fun maybeRequestNotificationPermission() {
        if (notificationPermissionRequested) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = PermissionUtils.notificationPermission ?: return
        if (PermissionUtils.hasNotificationAccess(this)) return

        val app = application as HookApplication
        lifecycleScope.launch {
            val prefs = app.preferencesRepository.userPreferencesFlow.first()
            // Only re-prompt users who are past onboarding — anyone still in it is
            // asked there. Re-check the flag and toggle after the suspend point.
            if (!prefs.hasCompletedOnboarding) return@launch
            if (notificationPermissionRequested) return@launch
            if (PermissionUtils.hasNotificationAccess(this@MainActivity)) return@launch
            notificationPermissionRequested = true
            notificationPermissionLauncher.launch(permission)
        }
    }

    /** The keyboard can't host a Play purchase sheet, so it sends the user here. */
    private fun handlePaywallIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_SHOW_PAYWALL, false) == true) {
            paywallRequest++
        }
    }

    companion object {
        /** Boolean extra: open straight onto the paywall route. */
        const val EXTRA_SHOW_PAYWALL = "com.tomfricks.hook.extra.SHOW_PAYWALL"
    }
}
