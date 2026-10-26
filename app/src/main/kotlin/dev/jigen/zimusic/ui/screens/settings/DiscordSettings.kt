package dev.jigen.zimusic.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.jigen.zimusic.MainActivity
import dev.jigen.zimusic.R
import dev.jigen.zimusic.preferences.DiscordPreferences
import dev.jigen.zimusic.discord.DiscordRpcManager

@Composable
fun DiscordSettings(
    navController: Any? = null,
) {
    val context = LocalContext.current as MainActivity
    var discordRPC by remember { mutableStateOf(DiscordPreferences.enableDiscordRPC) }
    
    val accessToken by DiscordRpcManager.accessTokenFlow.collectAsState()
    val isLoggedIn = !accessToken.isNullOrEmpty()
    val connectionStatus by DiscordRpcManager.connectionStatus.collectAsState()
    val fetchedUser by DiscordRpcManager.currentUser.collectAsState()

    val statusText = when {
        connectionStatus == DiscordRpcManager.Status.Connected -> stringResource(R.string.discord_status_connected)
        connectionStatus == DiscordRpcManager.Status.Authorizing -> stringResource(R.string.discord_status_authorizing)
        !DiscordRpcManager.isInitialized() -> stringResource(R.string.discord_status_not_initialized)
        DiscordRpcManager.isAuthorized() -> stringResource(R.string.discord_status_authorized)
        else -> ""
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        SwitchSettingsEntry(
            title = stringResource(R.string.enable_discord_rpc),
            text = stringResource(R.string.discord_connect_description),
            isChecked = discordRPC,
            onCheckedChange = {
                discordRPC = it
                DiscordPreferences.enableDiscordRPC = it
            }
        )

        SettingsEntry(
            title = if (isLoggedIn) "Disconnect Discord" else "Connect Discord",
            text = "Status: $statusText" + (fetchedUser?.username?.let { " ($it)" } ?: ""),
            onClick = {
                if (isLoggedIn) {
                    DiscordRpcManager.logout()
                } else {
                    dev.jigen.zimusic.ui.screens.discordLoginRoute.global()
                }
            }
        )
    }
}
