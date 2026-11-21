package dev.shinsou.kmp.network

import io.ktor.client.HttpClient

expect fun createPlatformHttpClient(): HttpClient
