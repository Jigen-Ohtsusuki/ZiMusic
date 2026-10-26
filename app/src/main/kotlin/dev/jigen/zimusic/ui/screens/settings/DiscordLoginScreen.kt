package dev.jigen.zimusic.ui.screens.settings

import android.annotation.SuppressLint
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import dev.jigen.compose.routing.LocalRouteHandler
import dev.jigen.zimusic.R
import dev.jigen.zimusic.discord.DiscordLog
import dev.jigen.zimusic.discord.DiscordTokenStore
import dev.jigen.zimusic.ui.components.themed.Header
import dev.jigen.zimusic.ui.screens.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val JS_SNIPPET =
    "javascript:(function()%7Bvar%20i%3Ddocument.createElement('iframe')%3Bdocument.body.appendChild(i)%3Balert(i.contentWindow.localStorage.token.slice(1,-1))%7D)()"

private const val MOTOROLA = "motorola"
private const val SAMSUNG_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; SM-S921U; Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36"

@SuppressLint("SetJavaScriptEnabled")
@Route
@Composable
fun DiscordLoginScreen() {
    val scope = rememberCoroutineScope()
    val routeHandler = LocalRouteHandler.current

    Column(modifier = Modifier.fillMaxSize()) {
        Header(title = stringResource(R.string.discord_integration))
        
        AndroidView(
            modifier = Modifier.weight(1f),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true

                    // Fix for Motorola devices - UA parsing issue breaks Discord login
                    if (Build.MANUFACTURER.equals(MOTOROLA, ignoreCase = true)) {
                        settings.userAgentString = SAMSUNG_USER_AGENT
                    }

                    webViewClient = object : WebViewClient() {
                        @Deprecated("Deprecated in Java")
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            url: String,
                        ): Boolean {
                            if (url.endsWith("/app")) {
                                view.stopLoading()
                                view.loadUrl(JS_SNIPPET)
                                view.visibility = View.GONE
                            }
                            return false
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onJsAlert(
                            view: WebView,
                            url: String,
                            message: String,
                            result: JsResult
                        ): Boolean {
                            DiscordLog.d("DiscordLogin", "Discord Token received")
                            if (message.isNotBlank() && message != "null" && message != "undefined") {
                                DiscordTokenStore.storeAccessToken(message)
                                dev.jigen.zimusic.discord.DiscordRpcManager.reconnectWithToken(message)
                                scope.launch(Dispatchers.Main) {
                                    routeHandler?.pop?.invoke()
                                }
                            }
                            view.visibility = View.GONE
                            result.confirm()
                            return true
                        }
                    }

                    loadUrl("https://discord.com/login")
                }
            }
        )
    }
}

