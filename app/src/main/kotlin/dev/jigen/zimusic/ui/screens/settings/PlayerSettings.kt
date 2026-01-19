package dev.jigen.zimusic.ui.screens.settings

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.media3.common.util.UnstableApi
import dev.jigen.core.ui.utils.isAtLeastAndroid6
import dev.jigen.zimusic.LocalPlayerServiceBinder
import dev.jigen.zimusic.R
import dev.jigen.zimusic.preferences.PlayerPreferences
import dev.jigen.zimusic.ui.screens.Route

@OptIn(UnstableApi::class)
@Route
@Composable
fun PlayerSettings() = with(PlayerPreferences) {
    LocalPlayerServiceBinder.current

    SettingsCategoryScreen(title = stringResource(R.string.player)) {
        SettingsGroup(title = stringResource(R.string.player)) {
            SwitchSettingsEntry(
                title = stringResource(R.string.persistent_queue),
                text = stringResource(R.string.persistent_queue_description),
                isChecked = persistentQueue,
                onCheckedChange = { persistentQueue = it }
            )

            if (isAtLeastAndroid6) SwitchSettingsEntry(
                title = stringResource(R.string.resume_playback),
                text = stringResource(R.string.resume_playback_description),
                isChecked = resumePlaybackWhenDeviceConnected,
                onCheckedChange = {
                    resumePlaybackWhenDeviceConnected = it
                }
            )

            SwitchSettingsEntry(
                title = stringResource(R.string.stop_when_closed),
                text = stringResource(R.string.stop_when_closed_description),
                isChecked = stopWhenClosed,
                onCheckedChange = { stopWhenClosed = it }
            )

            SwitchSettingsEntry(
                title = stringResource(R.string.pause_minimum_volume),
                text = stringResource(R.string.pause_minimum_volume_description),
                isChecked = stopOnMinimumVolume,
                onCheckedChange = { stopOnMinimumVolume = it }
            )

            SwitchSettingsEntry(
                title = stringResource(R.string.skip_on_error),
                text = stringResource(R.string.skip_on_error_description),
                isChecked = skipOnError,
                onCheckedChange = { skipOnError = it }
            )
        }
    }
}
