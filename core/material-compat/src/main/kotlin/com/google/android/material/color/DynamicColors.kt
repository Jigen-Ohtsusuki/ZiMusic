package com.google.android.material.color

import dev.jigen.core.ui.utils.isAtLeastAndroid12

@Suppress("unused")
object DynamicColors {
    @JvmStatic
    fun isDynamicColorAvailable() = isAtLeastAndroid12
}
