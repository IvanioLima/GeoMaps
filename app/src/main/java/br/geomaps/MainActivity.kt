package br.geomaps

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import br.geomaps.ui.GeoMapsTheme
import br.geomaps.ui.MainScreen
import br.geomaps.ui.MainViewModel
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true || res[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            vm.hub.restart()
        }
    }

    private val appUpdates by lazy { AppUpdateManagerFactory.create(this) }
    private val updateLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }

    /** Atualizações pelo Google Play: avisa e abre a tela de atualização do próprio Play. */
    private fun checkPlayUpdate(manual: Boolean) {
        appUpdates.appUpdateInfo.addOnSuccessListener { info ->
            val avail = info.updateAvailability()
            when {
                avail == UpdateAvailability.UPDATE_AVAILABLE && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) ||
                    avail == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS ->
                    runCatching {
                        appUpdates.startUpdateFlowForResult(info, updateLauncher, AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build())
                    }
                manual -> vm.toast("O GeoMaps está atualizado (versão ${BuildConfig.VERSION_NAME})")
            }
        }.addOnFailureListener { if (manual) vm.toast("Atualizações são feitas pelo Google Play (app instalado fora do Play)") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Barra de status opaca (ícones claros sobre a barra azul do app); o mapa não aparece atrás dela
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            GeoMapsTheme {
                MainScreen(vm)
            }
        }
        requestPermissions()
        handleIntent(intent)
        vm.playUpdateCheck = { manual -> checkPlayUpdate(manual) }
        if (savedInstanceState == null) checkPlayUpdate(manual = false)
        lifecycleScope.launch {
            vm.settings.collect { s ->
                if (s.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.hub.acquire("ui")
        vm.app.compass.acquire()
        // ao abrir (ou voltar para) o app: busca novidades no Google Drive
        vm.app.sync.requestSync()
    }

    override fun onResume() {
        super.onResume()
        // retoma uma atualização imediata que foi interrompida
        appUpdates.appUpdateInfo.addOnSuccessListener { info ->
            if (info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) runCatching {
                appUpdates.startUpdateFlowForResult(info, updateLauncher, AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build())
            }
        }
    }

    override fun onDestroy() {
        vm.playUpdateCheck = null
        super.onDestroy()
    }

    override fun onStop() {
        vm.hub.release("ui")
        vm.app.compass.release()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) vm.importUri(uri)
    }

    private fun requestPermissions() {
        val needed = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) permLauncher.launch(needed.toTypedArray())
    }
}
