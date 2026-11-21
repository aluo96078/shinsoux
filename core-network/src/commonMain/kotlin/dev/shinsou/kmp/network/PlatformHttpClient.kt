package dev.shinsou.kmp.network

import io.ktor.client.HttpClient

public expect fun createPlatformHttpClient(): HttpClient
