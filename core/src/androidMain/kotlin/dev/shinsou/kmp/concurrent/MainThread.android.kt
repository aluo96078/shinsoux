package dev.shinsou.kmp.concurrent

import android.os.Looper

public actual fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()
