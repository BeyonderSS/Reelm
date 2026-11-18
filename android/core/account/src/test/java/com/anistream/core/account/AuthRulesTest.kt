package com.anistream.core.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRulesTest {
    @Test fun usernameMatchesServerRules() {
        assertNull(AuthRules.usernameError(""))
        assertNull(AuthRules.usernameError("neo_1"))
        assertEquals("At least 3 characters", AuthRules.usernameError("ab"))
        assertEquals("At most 32 characters", AuthRules.usernameError("a".repeat(33)))
        assertEquals("Use only lowercase letters, numbers and _", AuthRules.usernameError("Neo"))
        assertEquals("Use only lowercase letters, numbers and _", AuthRules.usernameError("ne o"))
    }

    @Test fun passwordAndSubmit() {
        assertNull(AuthRules.passwordError(""))
        assertEquals("At least 8 characters", AuthRules.passwordError("short"))
        assertTrue(AuthRules.canSubmit("neo", "12345678"))
        assertFalse(AuthRules.canSubmit("neo", "1234567"))
        assertFalse(AuthRules.canSubmit("Neo", "12345678"))
    }

    @Test fun friendlyErrors() {
        assertEquals("That username is taken. Try another.", AuthRules.friendly(ApiException("username taken", 409), signingUp = true))
        assertEquals("Profile limit reached (5)", AuthRules.friendly(ApiException("profile limit reached (5)", 409)))
        assertEquals("That username and password don't match.", AuthRules.friendly(ApiException("wrong username or password", 401)))
        assertEquals(3, FluentEmojis.groups.size)
        assertEquals(24, FluentEmojis.groups.sumOf { it.second.size })
    }
}

class SessionRevokedTest {
    @Test fun onlyTheCurrentTokenEndsTheSession() {
        assertTrue(shouldEndSession("t1", "t1"))
        assertFalse(shouldEndSession("t1", null)) // guest, or already signed out by an earlier 401
        assertFalse(shouldEndSession("old", "t2")) // stale token after a new sign-in
    }
}
