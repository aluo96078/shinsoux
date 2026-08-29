package dev.shinsou.kmp.ui.screens

import dev.shinsou.kmp.ui.SourceLoginResult
import dev.shinsou.kmp.ui.SourceLoginFailureStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceLoginFeedbackTest {
    @Test
    fun successfulLoginProducesVisibleSuccessMessage() {
        val feedback = sourceLoginFeedback(
            result = SourceLoginResult(succeeded = true),
            successMessage = "登入成功。",
            fallbackErrorMessage = "登入失敗。",
        )

        assertEquals("登入成功。", feedback.successMessage)
        assertNull(feedback.errorMessage)
    }

    @Test
    fun failedLoginPreservesSafeSourceMessage() {
        val feedback = sourceLoginFeedback(
            result = SourceLoginResult(succeeded = false, errorMessage = "帳號或密碼錯誤"),
            successMessage = "登入成功。",
            fallbackErrorMessage = "登入失敗。",
        )

        assertNull(feedback.successMessage)
        assertEquals("帳號或密碼錯誤", feedback.errorMessage)
    }

    @Test
    fun failedLoginWithoutSourceMessageUsesHostFallback() {
        val feedback = sourceLoginFeedback(
            result = SourceLoginResult(succeeded = false, errorMessage = ""),
            successMessage = "登入成功。",
            fallbackErrorMessage = "登入失敗。",
        )

        assertNull(feedback.successMessage)
        assertEquals("登入失敗。", feedback.errorMessage)
    }

    @Test
    fun stagedFailureUsesSafeStageMessageWithoutRawExceptionText() {
        val feedback = sourceLoginFeedback(
            result = SourceLoginResult(
                succeeded = false,
                failureStage = SourceLoginFailureStage.AUTHENTICATE,
            ),
            successMessage = "登入成功。",
            fallbackErrorMessage = "登入失敗。",
            failureMessage = { "階段：${sourceLoginFailureMessageKey(it)}" },
        )

        assertNull(feedback.successMessage)
        assertEquals(
            "階段：The source login process failed before returning a response.",
            feedback.errorMessage,
        )
    }

    @Test
    fun safeSourceMessageTakesPrecedenceOverFailureStage() {
        val feedback = sourceLoginFeedback(
            result = SourceLoginResult(
                succeeded = false,
                errorMessage = "網站要求 Cloudflare 驗證。",
                failureStage = SourceLoginFailureStage.AUTHENTICATE,
            ),
            successMessage = "登入成功。",
            fallbackErrorMessage = "登入失敗。",
            failureMessage = { "不應顯示" },
        )

        assertEquals("網站要求 Cloudflare 驗證。", feedback.errorMessage)
    }
}
