package dev.shinsou.kmp.plugin

/**
 * Time limits of a package's network traffic, in one place for every platform (R4-5,
 * docs/architecture/0006). Like [PluginExecutionLimits], packages cannot change them.
 */
internal object PluginNetworkLimits {
    /** Opening the connection, TLS included. */
    public const val CONNECT_TIMEOUT_MILLIS: Long = 15_000L

    /** One whole request, body included: a server trickling bytes cannot hold a thread longer. */
    public const val REQUEST_TIMEOUT_MILLIS: Long = 45_000L

    /** What a screen waits for one host-side step it shows a spinner for (an image, a logout). */
    public const val UI_WAIT_MILLIS: Long = 30_000L

    /** A remote logout; on timeout the local session is kept so the user can retry. */
    public const val LOGOUT_WAIT_MILLIS: Long = 10_000L
}
