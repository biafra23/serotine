package eth.serotine.android.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import eth.serotine.android.R
import eth.serotine.android.SerotineApplication
import eth.serotine.domain.model.Network
import eth.serotine.ui.ratewidget.WidgetNetworkScreen
import eth.serotine.ui.ratewidget.WidgetNetworkUiState
import eth.serotine.ui.theme.SerotineTheme
import kotlinx.coroutines.launch

/**
 * The exchange-rate widget's configuration (docs/networks.md, *The widget*): when a widget is
 * placed, the launcher opens this to ask which network's rate it shows, offering the networks that
 * are on and have accounts ([SerotineApplication.widgetNetworkChoices]). The choice is kept for
 * that widget alone ([RateWidgetNetworks]); another network means placing another widget.
 * Cancelled, or with nothing to choose, the widget is not placed. Exported, as the launcher, another
 * app, opens it; it acts only on a widget id of this app's own [RateWidget] whose network is not
 * chosen yet.
 */
class RateWidgetConfigureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Until a network is chosen, leaving here places no widget.
        setResult(RESULT_CANCELED)
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.provider
        // Only a widget of this app's being placed: one placed keeps its network (another is another widget).
        if (provider != ComponentName(this, RateWidget::class.java) || RateWidgetNetworks(this).chosen(id)) {
            finish()
            return
        }
        val app = application as SerotineApplication
        var state by mutableStateOf(WidgetNetworkUiState(emptyList()))
        lifecycleScope.launch { state = app.widgetNetworkChoices() }
        enableEdgeToEdge()
        setContent {
            SerotineTheme {
                WidgetNetworkScreen(
                    state,
                    onChoose = { chainId -> choose(app, id, chainId) },
                    onCancel = ::finish,
                )
            }
        }
    }

    /** Whether a choice is being stored: a second tap meanwhile does nothing. */
    private var choosing = false

    private fun choose(app: SerotineApplication, id: Int, chainId: Long) {
        val network = Network.ofChainId(chainId) ?: return
        if (choosing) return
        choosing = true
        lifecycleScope.launch {
            if (!app.rateWidgetConfigured(id, network)) {
                choosing = false
                Toast.makeText(this@RateWidgetConfigureActivity, R.string.rate_widget_not_saved, Toast.LENGTH_LONG)
                    .show()
                return@launch
            }
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
            finish()
        }
    }
}
