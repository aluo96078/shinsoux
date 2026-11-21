package dev.shinsou.kmp.concurrent

import android.os.Looper

actual fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()
