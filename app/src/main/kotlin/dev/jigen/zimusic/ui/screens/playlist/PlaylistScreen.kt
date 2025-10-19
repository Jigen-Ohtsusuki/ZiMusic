package dev.jigen.zimusic.ui.screens.playlist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import dev.jigen.zimusic.R
import dev.jigen.zimusic.ui.components.themed.Scaffold
import dev.jigen.zimusic.ui.screens.GlobalRoutes
import dev.jigen.zimusic.ui.screens.Route
import dev.jigen.compose.persist.PersistMapCleanup
import dev.jigen.compose.routing.RouteHandler

@Route
@Composable
fun PlaylistScreen(
    browseId: String,
    params: String?,
    shouldDedup: Boolean,
    maxDepth: Int? = null
) {
    val saveableStateHolder = rememberSaveableStateHolder()
    PersistMapCleanup(prefix = "playlist/$browseId")

    RouteHandler {
        GlobalRoutes()

        Content {
            Scaffold(
                key = "playlist",
                topIconButtonId = R.drawable.chevron_back,
                onTopIconButtonClick = pop,
                tabIndex = 0,
                onTabChange = { },
                tabColumnContent = {
                    tab(0, R.string.songs, R.drawable.musical_notes)
                }
            ) { currentTabIndex ->
                saveableStateHolder.SaveableStateProvider(key = currentTabIndex) {
                    when (currentTabIndex) {
                        0 -> PlaylistSongList(
                            browseId = browseId,
                            params = params,
                            maxDepth = maxDepth,
                            shouldDedup = shouldDedup
                        )
                    }
                }
            }
        }
    }
}
