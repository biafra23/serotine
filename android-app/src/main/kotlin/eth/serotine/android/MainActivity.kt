package eth.serotine.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import eth.serotine.ui.App

/** Renders the shared [App]; all state lives in the ViewModels the activity scopes. */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as SerotineApplication).graph
        setContent {
            App(graph)
        }
    }
}
