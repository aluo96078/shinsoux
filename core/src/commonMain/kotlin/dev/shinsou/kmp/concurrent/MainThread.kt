package dev.shinsou.kmp.concurrent

/** Whether the caller runs on the platform's UI thread, where blocking I/O stalls frames. */
public expect fun isMainThread(): Boolean
