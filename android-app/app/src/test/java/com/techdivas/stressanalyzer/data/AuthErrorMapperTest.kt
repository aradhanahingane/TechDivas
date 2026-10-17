package com.techdivas.stressanalyzer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AuthErrorMapperTest {
    @Test
    fun duplicateEmailHasActionableMessage() {
        assertEquals(
            "An account already uses this email. Try signing in instead.",
            AuthErrorMapper.messageForCode("ERROR_EMAIL_ALREADY_IN_USE")
        )
    }

    @Test
    fun weakPasswordHasActionableMessage() {
        assertEquals(
            "Choose a stronger password with at least 6 characters.",
            AuthErrorMapper.messageForCode("ERROR_WEAK_PASSWORD")
        )
    }

    @Test
    fun invalidCredentialsDoNotRevealWhichFieldWasWrong() {
        assertEquals(
            "That email and password do not match. Check them and try again.",
            AuthErrorMapper.messageForCode("ERROR_INVALID_LOGIN_CREDENTIALS")
        )
    }
}
