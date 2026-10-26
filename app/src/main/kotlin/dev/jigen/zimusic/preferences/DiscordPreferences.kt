package dev.jigen.zimusic.preferences

import dev.jigen.zimusic.GlobalPreferencesHolder

object DiscordPreferences : GlobalPreferencesHolder() {
    val enableDiscordRPCProperty = boolean(true)
    var enableDiscordRPC by enableDiscordRPCProperty
}
