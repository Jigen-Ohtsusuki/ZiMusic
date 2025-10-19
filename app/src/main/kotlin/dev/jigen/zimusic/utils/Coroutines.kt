package dev.jigen.zimusic.utils

import android.os.CancellationSignal
import kotlinx.coroutines.CancellableContinuation

val <T> CancellableContinuation<T>.asCancellationSignal get() = CancellationSignal().also {
    it.setOnCancelListener { cancel() }
}
