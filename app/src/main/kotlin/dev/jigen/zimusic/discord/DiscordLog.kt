package dev.jigen.zimusic.discord

import android.util.Log

object DiscordLog {
    fun v(tag: String, msg: String, vararg args: Any?) { Log.v(tag, if (args.isEmpty()) msg else String.format(msg, *args)) }
    fun d(tag: String, msg: String, vararg args: Any?) { Log.d(tag, if (args.isEmpty()) msg else String.format(msg, *args)) }
    fun i(tag: String, msg: String, vararg args: Any?) { Log.i(tag, if (args.isEmpty()) msg else String.format(msg, *args)) }
    fun w(tag: String, msg: String, vararg args: Any?) { Log.w(tag, if (args.isEmpty()) msg else String.format(msg, *args)) }
    fun w(tag: String, msg: String, throwable: Throwable?) { Log.w(tag, msg, throwable) }
    fun e(tag: String, msg: String, vararg args: Any?) { Log.e(tag, if (args.isEmpty()) msg else String.format(msg, *args)) }
    fun e(tag: String, msg: String, throwable: Throwable?) { Log.e(tag, msg, throwable) }
}

