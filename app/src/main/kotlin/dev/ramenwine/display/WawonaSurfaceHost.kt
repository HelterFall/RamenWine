package dev.ramenwine.display

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Hosts Wawona's Android SurfaceView inside the RamenWine Compose UI.
 * Surface ownership stays with DisplaySession and is detached on disposal.
 */
@Composable
fun WawonaSurfaceHost(
    session: DisplaySession,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = remember(session, context) { WawonaSurfaceView(context, session) }

    DisposableEffect(view, session) {
        onDispose {
            val surface = view.holder.surface
            if (surface.isValid) {
                session.detachSurface(surface)
            }
            view.holder.removeCallback(view)
        }
    }

    AndroidView(
        factory = { view },
        modifier = modifier
    )
}
