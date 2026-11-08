package io.github.nikarpoff.alv.view

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.nikarpoff.alv.BuildConfig
import io.github.nikarpoff.alv.controller.AssistantController
import io.github.nikarpoff.alv.controller.CameraController
import io.github.nikarpoff.alv.controller.DebugJournal
import io.github.nikarpoff.alv.controller.Earcon
import io.github.nikarpoff.alv.controller.ExecutorLoop
import io.github.nikarpoff.alv.controller.MicrophoneController
import io.github.nikarpoff.alv.controller.Notice
import io.github.nikarpoff.alv.controller.PassiveModeSetting
import io.github.nikarpoff.alv.controller.PermissionFlow
import io.github.nikarpoff.alv.controller.PermissionFlow.Permission
import io.github.nikarpoff.alv.controller.SessionController
import io.github.nikarpoff.alv.controller.SettingsRepository
import io.github.nikarpoff.alv.model.Settings
import io.github.nikarpoff.alv.model.protocol.Network
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The only activity: builds the controllers and the View and follows the lifecycle.
 *
 * On the screen (MOB-8, MOB-12): the session is open, the camera analyses frames, the screen does not
 * go dark. Off the screen everything stops. Permissions go «explanation → request → result» (MOB-15).
 */
class MainActivity : ComponentActivity() {
    private val journal = DebugJournal { Log.d(TAG, it) }
    private val loop = ExecutorLoop()
    private val settings = MutableStateFlow<Settings?>(null)
    private val settingsOpen = MutableStateFlow(false)
    private val permissions = PermissionFlow()
    private var permissionsInFlight = false

    private lateinit var repository: SettingsRepository
    private lateinit var passiveSetting: PassiveModeSetting
    private lateinit var session: SessionController
    private lateinit var camera: CameraController
    private lateinit var microphone: MicrophoneController
    private lateinit var feedback: Feedback
    private lateinit var speech: FallbackSpeech
    private lateinit var player: AnswerPlayer
    private lateinit var controller: AssistantController

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        run(permissions.onResult(granted()))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light system bar icons on the black screen regardless of the system theme
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // MOB-12: the screen does not go dark while the app is open
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        repository = SettingsRepository(this, BuildConfig.DEFAULT_SERVER_URL, BuildConfig.DEFAULT_DEVICE_TOKEN)
        passiveSetting = PassiveModeSetting(repository)
        session = SessionController(loop)
        camera = CameraController(this)
        feedback = Feedback(this, journal)
        speech = FallbackSpeech(this, journal) { feedback.signal(Earcon.ERROR, System.nanoTime(), vibrate = false) }
        player = AnswerPlayer(this, speech)
        microphone = MicrophoneController(this) { pcm, voiced -> controller.onAudio(pcm, voiced) }
        controller = AssistantController(
            loop = loop,
            session = session,
            camera = camera,
            microphone = microphone,
            playback = player,
            signals = feedback,
            announcer = feedback,
            passiveSetting = passiveSetting,
            journal = journal,
            scope = lifecycleScope,
            network = ::network,
            appVersion = BuildConfig.VERSION_NAME,
            platform = "android-${Build.VERSION.SDK_INT}",
            pxPerDp = resources.displayMetrics.density,
        )
        session.listener = controller
        player.listener = controller

        lifecycleScope.launch {
            val passive = passiveSetting.restore()
            settings.value = repository.loadSettings().copy(passiveMode = passive)
        }
        if (intent?.action == ACTION_SETTINGS) openSettings()

        setContent {
            AlvTheme {
                val screen by controller.screen.collectAsState()
                val current by settings.collectAsState()
                val inSettings by settingsOpen.collectAsState()
                val debug by journal.values.collectAsState()
                val loaded = current
                if (inSettings && loaded != null) {
                    SettingsScreen(settings = loaded, onSave = ::saveSettings, onCancel = ::closeSettings)
                } else {
                    AssistantScreen(
                        screen = screen,
                        debug = if (loaded?.showDebugPanel == true) debug else null,
                        controller = controller,
                        onSettings = ::openSettings,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_SETTINGS) openSettings()
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            controller.start(settings.filterNotNull().first())
            if (settingsOpen.value) controller.pause()
        }
        if (!permissionsInFlight) run(permissions.start(granted()))
    }

    override fun onStop() {
        controller.stop()
        super.onStop()
    }

    override fun onDestroy() {
        player.release()
        speech.release()
        feedback.release()
        camera.shutdown()
        microphone.stop()
        loop.shutdown()
        super.onDestroy()
    }

    /** Performs a step of the permission flow (MOB-15). */
    private fun run(step: PermissionFlow.Step) {
        permissionsInFlight = step !is PermissionFlow.Step.Ready && step !is PermissionFlow.Step.Blocked
        when (step) {
            PermissionFlow.Step.Explain ->
                feedback.announce(Notice.PermissionRationale) { runOnUiThread { run(permissions.onExplained()) } }
            is PermissionFlow.Step.Request ->
                permissionRequest.launch(step.permissions.map(::manifestName).toTypedArray())
            is PermissionFlow.Step.ExplainDenied ->
                feedback.announce(Notice.PermissionDenied) { runOnUiThread { run(permissions.onDeniedExplained()) } }
            PermissionFlow.Step.Ready -> {
                camera.bind(this)
                controller.onPermissions(true)
            }
            is PermissionFlow.Step.Blocked -> controller.onPermissions(false)
        }
    }

    private fun granted(): Set<Permission> = Permission.entries.filter {
        ContextCompat.checkSelfPermission(this, manifestName(it)) == PackageManager.PERMISSION_GRANTED
    }.toSet()

    private fun manifestName(permission: Permission): String = when (permission) {
        Permission.CAMERA -> Manifest.permission.CAMERA
        Permission.MICROPHONE -> Manifest.permission.RECORD_AUDIO
    }

    private fun openSettings() {
        settingsOpen.value = true
        controller.pause()
    }

    private fun closeSettings() {
        settingsOpen.value = false
        settings.value?.let { controller.resume(it) }
    }

    private fun saveSettings(edited: Settings) {
        settings.value = edited
        settingsOpen.value = false
        controller.resume(edited)
        lifecycleScope.launch { repository.saveSettings(edited) }
    }

    /** Network of the phone for `hello` and `client.metrics`. */
    private fun network(): Network {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return Network.OTHER
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return Network.OTHER
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Network.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Network.CELLULAR
            else -> Network.OTHER
        }
    }

    companion object {
        /** Intent action of the launcher shortcut that opens the settings. */
        const val ACTION_SETTINGS = "io.github.nikarpoff.alv.action.SETTINGS"

        private const val TAG = "alv"
    }
}
