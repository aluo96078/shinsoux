package dev.shinsou.kmp.concurrent

import android.os.Looper

internal actual fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()
