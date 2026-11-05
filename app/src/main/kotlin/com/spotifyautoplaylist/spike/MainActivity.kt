package com.spotifyautoplaylist.spike

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.spotifyautoplaylist.onboarding.*
import com.spotifyautoplaylist.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val container = (application as ReplayApplication).container
        setContent {
            val model: OnboardingViewModel = viewModel(factory = viewModelFactory {
                initializer { OnboardingViewModel(container.onboardingAuth, container.playlistSetup, container.sync?.let { s -> SyncScheduleHook { s.ensureScheduled() } }) }
            })
            val state by model.state.collectAsStateWithLifecycle()
            var preview by rememberSaveable { mutableStateOf(false) }
            var externalError by remember { mutableStateOf<String?>(null) }
            SideEffect {
                // Only account-free preview and public Welcome are capturable for visual QA.
                if (preview || state.stage == OnboardingStage.WELCOME) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            val lifecycleOwner = LocalLifecycleOwner.current
            LaunchedEffect(model, lifecycleOwner) {
                lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    model.browserRequests.collect { request ->
                        if (request.completion.isActive) {
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, request.uri.toString().toUri()).apply {
                                    addCategory(Intent.CATEGORY_BROWSABLE)
                                })
                                request.completion.complete(true)
                            } catch (_: ActivityNotFoundException) {
                                request.completion.complete(false)
                            } catch (_: SecurityException) {
                                request.completion.complete(false)
                            }
                        }
                    }
                }
            }
            ReplayTheme {
                if (preview || state.stage == OnboardingStage.READY) {
                    ReplayFoundation(
                        preview = preview,
                        playlistName = if (preview) null else state.playlist?.name,
                        onOpenPlaylist = if (preview) null else state.playlist?.let { playlist -> ({
                            // HTTPS App Link resolves Spotify; browser handles the URL if unavailable.
                            try { startActivity(Intent(Intent.ACTION_VIEW, playlist.url.toUri())) }
                            catch (_: ActivityNotFoundException) { externalError = "Không mở được Spotify hoặc trình duyệt. Hãy kiểm tra ứng dụng mặc định." }
                            catch (_: SecurityException) { externalError = "Không thể mở liên kết Spotify trên máy này." }
                        }) },
                        onExitPreview = { preview = false }, onDisconnect = model::disconnect,
                        version = BuildConfig.VERSION_NAME,
                    )
                } else OnboardingScreen(state, model, onPreview = { preview = true })
                externalError?.let { message ->
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { externalError = null },
                        text = { androidx.compose.material3.Text(message) },
                        confirmButton = { androidx.compose.material3.TextButton(onClick = { externalError = null }) {
                            androidx.compose.material3.Text("Đóng")
                        } },
                    )
                }
            }
        }
    }
}
