package eth.serotine.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import eth.serotine.ui.App

/** Renders the shared [App]; all state lives in the ViewModels the activity scopes. */
class MainActivity : ComponentActivity() {

    // Nothing to do with the answer: without the permission, upgrade notices are dropped and
    // the app's own screens still say them.
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as SerotineApplication).graph
        setContent {
            App(graph)
        }
        if (savedInstanceState == null) askForNotifications()
    }

    /**
     * Asks for the permission to notify (Android 13 and later), so a network upgrade can be
     * announced while the app is closed. Android itself stops showing the question once the
     * user has said no twice, so a launch after that asks nothing.
     */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
