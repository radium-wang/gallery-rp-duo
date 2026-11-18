// Modified for Retroid Pocket Duo by radium-wang, 2026.
/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.common.chat

import android.app.ActivityOptions
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.google.ai.edge.gallery.ui.theme.GalleryTheme

private const val TAG = "AGSecondaryChatComposer"

private class ComposerSession(
  val content: State<@Composable () -> Unit>,
  val editorFocused: State<Boolean>,
  val keyboardPinned: MutableState<Boolean>,
  val keyboardVisible: MutableState<Boolean>,
  val windowFocused: MutableState<Boolean>,
  val onShowingChanged: (Boolean) -> Unit,
  val onDismiss: () -> Unit,
)

// The active chat owns this session. The second Activity renders its controls in the same process.
private var activeComposer by mutableStateOf<ComposerSession?>(null)

@Composable
internal fun rememberSecondaryChatDisplay(): Display? {
  val context = LocalContext.current
  val manager = remember(context) { context.getSystemService(DisplayManager::class.java) }
  fun findDisplay(): Display? =
    manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull {
      it.displayId != context.display?.displayId && it.state != Display.STATE_OFF
    }

  var display by remember(manager) { mutableStateOf(findDisplay()) }
  DisposableEffect(manager) {
    val listener = object : DisplayManager.DisplayListener {
      override fun onDisplayAdded(displayId: Int) {
        display = findDisplay()
      }

      override fun onDisplayRemoved(displayId: Int) {
        display = findDisplay()
      }

      override fun onDisplayChanged(displayId: Int) {
        display = findDisplay()
      }
    }
    manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
    onDispose { manager.unregisterDisplayListener(listener) }
  }
  return display
}

/** Moves the current chat's controls to another display without starting another model. */
@Composable
internal fun SecondaryChatComposer(
  display: Display,
  editorFocused: State<Boolean>,
  keyboardPinned: MutableState<Boolean>,
  keyboardVisible: MutableState<Boolean>,
  windowFocused: MutableState<Boolean>,
  onShowingChanged: (Boolean) -> Unit,
  onDismiss: () -> Unit,
  content: @Composable () -> Unit,
) {
  val context = LocalContext.current
  val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
  if (!lifecycleState.isAtLeast(Lifecycle.State.STARTED)) return

  val latestContent = rememberUpdatedState(content)
  val latestDismiss by rememberUpdatedState(onDismiss)
  val latestShowingChanged by rememberUpdatedState(onShowingChanged)

  DisposableEffect(context, display.displayId) {
    val session = ComposerSession(
      content = latestContent,
      editorFocused = editorFocused,
      keyboardPinned = keyboardPinned,
      keyboardVisible = keyboardVisible,
      windowFocused = windowFocused,
      onShowingChanged = { latestShowingChanged(it) },
      onDismiss = { latestDismiss() },
    )
    val intent = Intent(context, SecondaryChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
      activeComposer = session
      val options = ActivityOptions.makeBasic().setLaunchDisplayId(display.displayId)
      context.startActivity(intent, options.toBundle())
    } catch (e: RuntimeException) {
      Log.w(TAG, "Secondary display unavailable", e)
      latestDismiss()
    }

    onDispose {
      if (activeComposer === session) activeComposer = null
      latestShowingChanged(false)
    }
  }
}

class SecondaryChatActivity : ComponentActivity() {
  private var session: ComposerSession? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    session = activeComposer
    if (session == null || display?.displayId == Display.DEFAULT_DISPLAY) {
      finish()
      return
    }

    enableEdgeToEdge()
    val root = findViewById<View>(android.R.id.content)
    var imeInsetPx by mutableIntStateOf(0)
    var navigationInsetPx by mutableIntStateOf(0)
    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
      imeInsetPx = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
      session?.keyboardVisible?.value = insets.isVisible(WindowInsetsCompat.Type.ime())
      navigationInsetPx = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
      insets
    }
    setContent {
      val currentSession = activeComposer
      if (currentSession == null || currentSession !== session) {
        LaunchedEffect(currentSession) { finish() }
      } else {
        LaunchedEffect(currentSession) { currentSession.onShowingChanged(true) }
        val editing = currentSession.editorFocused.value
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        BackHandler(enabled = editing) {
          if (currentSession.keyboardPinned.value) {
            keyboard?.show()
          } else {
            keyboard?.hide()
            focus.clearFocus(force = true)
          }
        }
        val keyboardVisible = imeInsetPx > 0
        var wasKeyboardVisible by remember { mutableStateOf(false) }
        LaunchedEffect(keyboardVisible) {
          if (wasKeyboardVisible && !keyboardVisible && !currentSession.keyboardPinned.value) {
            focus.clearFocus(force = true)
          }
          wasKeyboardVisible = keyboardVisible
        }
        val bottomInsetPx = when {
          keyboardVisible -> imeInsetPx
          editing -> windowManager.maximumWindowMetrics.bounds.height() / 2
          else -> navigationInsetPx
        }
        val bottomInset = with(LocalDensity.current) { bottomInsetPx.toDp() }
        GalleryTheme {
          Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize().padding(bottom = bottomInset)) {
              Column(
                modifier = Modifier.align(Alignment.BottomCenter)
                  .fillMaxWidth()
                  .verticalScroll(rememberScrollState()),
              ) {
                currentSession.content.value()
              }
            }
          }
        }
      }
    }
    ViewCompat.requestApplyInsets(root)
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    session?.windowFocused?.value = hasFocus
  }

  override fun onDestroy() {
    val finishingSession = session
    if (isFinishing && finishingSession != null && activeComposer === finishingSession) {
      activeComposer = null
      finishingSession.onShowingChanged(false)
      finishingSession.onDismiss()
    }
    super.onDestroy()
  }
}
