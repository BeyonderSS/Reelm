package com.anistream.feature.party

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartyTest {
    @Test fun tagMapsToVersionCode() {
        assertEquals(107, tagToVersionCode("v1.0.7"))
        assertEquals(100, tagToVersionCode("1.0.0"))
        assertEquals(112, tagToVersionCode(" v1.0.12 "))
        assertNull(tagToVersionCode("v2.0.1"))
        assertNull(tagToVersionCode("v1.0.7-beta"))
        assertNull(tagToVersionCode("nightly"))
    }

    @Test fun assetSelectionSeparatesFlavors() {
        val assets = listOf(
            "Reelm-TV-v1.0.7.apk" to "tv-url",
            "Reelm-v1.0.7.apk" to "phone-url",
            "Reelm-v1.0.7.apk.sha256" to "sum-url"
        )
        assertEquals("phone-url", pickAsset(assets, tv = false))
        assertEquals("tv-url", pickAsset(assets, tv = true))
        assertNull(pickAsset(listOf("source.zip" to "z"), tv = false))
    }

    @Test fun offerRules() {
        assertTrue(shouldOffer(106, 107, debuggable = false))
        assertFalse(shouldOffer(107, 107, debuggable = false))
        assertFalse(shouldOffer(120, 107, debuggable = false)) // local build above latest
        assertFalse(shouldOffer(1, 107, debuggable = false)) // local build default versionCode
        assertFalse(shouldOffer(106, 107, debuggable = true))
    }

    @Test fun releaseJsonPicksFlavorAsset() {
        val json = Json.parseToJsonElement(
            """{"tag_name":"v1.0.9","name":"Reelm 9","body":"Fixes","assets":[
              {"name":"Reelm-TV-v1.0.9.apk","browser_download_url":"https://x/tv.apk","size":5},
              {"name":"Reelm-v1.0.9.apk","browser_download_url":"https://x/phone.apk","size":7}]}"""
        ).jsonObject
        assertEquals("https://x/phone.apk", parseRelease(json, tv = false)?.apkUrl)
        assertEquals("https://x/tv.apk", parseRelease(json, tv = true)?.apkUrl)
        assertEquals(109, parseRelease(json, tv = true)?.versionCode)
    }

    @Test fun appLatestPicksFlavorAndChecksum() {
        val sha = "ab".repeat(32)
        val json = Json.parseToJsonElement(
            """{"version":"1.0.46","notes":"Fixes","phone":{"url":"https://api/dl/Reelm-v1.0.46.apk","size":8414369,"sha256":"$sha"},
              "tv":{"url":"https://api/dl/Reelm-TV-v1.0.46.apk","size":7,"sha256":null}}"""
        ).jsonObject
        val phone = parseAppLatest(json, tv = false)!!
        assertEquals(146, phone.versionCode)
        assertEquals("https://api/dl/Reelm-v1.0.46.apk", phone.apkUrl)
        assertEquals(8414369L, phone.sizeBytes)
        assertEquals(sha, phone.sha256)
        assertEquals("Fixes", phone.body)
        assertNull(parseAppLatest(json, tv = true)!!.sha256)
        assertNull(parseAppLatest(Json.parseToJsonElement("""{"version":"1.0.46","tv":null}""").jsonObject, tv = true))
        assertNull(parseAppLatest(Json.parseToJsonElement("""{"version":"2.0","phone":{"url":"x"}}""").jsonObject, tv = false))
        // GitHub's own asset digest is used on the fallback path
        val gh = Json.parseToJsonElement(
            """{"tag_name":"v1.0.45","assets":[{"name":"Reelm-v1.0.45.apk","browser_download_url":"u","size":1,"digest":"sha256:$sha"}]}"""
        ).jsonObject
        assertEquals(sha, parseRelease(gh, tv = false)?.sha256)
    }

    @Test fun playingRoomAdvancesByElapsedServerTime() {
        assertEquals(12_500L, targetPosition(10_000, at = 1_000, serverNow = 3_500, playing = true))
        assertEquals(10_000L, targetPosition(10_000, at = 1_000, serverNow = 3_500, playing = false))
        // clock skew can put "now" before "at": never seek backwards
        assertEquals(10_000L, targetPosition(10_000, at = 2_000, serverNow = 1_900, playing = true))
    }

    @Test fun driftThreshold() {
        assertFalse(shouldSeek(10_000, 11_499))
        assertTrue(shouldSeek(10_000, 11_500))
        assertTrue(shouldSeek(12_000, 10_000))
    }

    @Test fun positionsAreIntegerMsAndDecimalsRound() {
        fun p(s: String) = ms(Json.parseToJsonElement(s))
        assertEquals(153_000L, p("153000"))
        assertEquals(119L, p("118.67")) // an old web client's float is rounded, not dropped
        assertEquals(0L, p("0"))
        assertNull(p("\"abc\""))
        assertNull(p("null"))
        assertNull(ms(null))
    }

    @Test fun lightsDownHoldsUntilCountdownEnds() {
        // `at` = countdownAt in the future: stay on the frame
        assertEquals(60_000L, targetPosition(60_000, at = 10_000, serverNow = 7_000, playing = true))
        assertEquals(61_000L, targetPosition(60_000, at = 10_000, serverNow = 11_000, playing = true))
    }

    @Test fun echoGuardKeepsJoinerQuiet() {
        var now = 1_000L
        val joiner = EchoGuard({ now }, host = false)
        assertFalse(joiner.mayBroadcast()) // before the room state is applied (autoplay at 0 must not go out)
        now += 60_000
        assertFalse(joiner.mayBroadcast())
        joiner.applying()
        assertFalse(joiner.mayBroadcast())
        joiner.joined()
        now += JOIN_QUIET_MS - 1
        assertFalse(joiner.mayBroadcast())
        now += 1
        assertTrue(joiner.mayBroadcast())
        joiner.applying()
        now += APPLY_QUIET_MS - 1
        assertFalse(joiner.mayBroadcast()) // applying a remote event: the player's own echo stays local
        now += 1
        assertTrue(joiner.mayBroadcast())

        val host = EchoGuard({ now }, host = true)
        assertTrue(host.mayBroadcast())
        host.applying()
        assertFalse(host.mayBroadcast())
    }

    @Test fun duckingWaitsThenRecovers() {
        val d = Ducker()
        assertEquals(1f, d.volume(0, loud = true))
        assertEquals(1f, d.volume(100, loud = true)) // a click under 150 ms doesn't duck
        assertEquals(DUCK_LEVEL, d.volume(160, loud = true))
        assertEquals(DUCK_LEVEL, d.volume(1_000, loud = true))
        assertEquals(DUCK_LEVEL, d.volume(1_100, loud = false))
        val mid = d.volume(1_300, loud = false)
        assertTrue(mid > DUCK_LEVEL && mid < 1f)
        assertEquals(1f, d.volume(1_500, loud = false))
        assertEquals(1f, d.volume(1_600, loud = true)) // a fresh 150 ms is needed again
    }

    @Test fun sourceMatching() {
        val room = sourceOf("Hotstar", "1080p", "cdn.a.com", "Hotstar · 1080p · 2.1 GB")
        val ours = listOf(
            sourceOf("VegaMovies", "1080p", "v.com", "VegaMovies · 1080p"),
            sourceOf("hotstar", "720p", "cdn.a.com", "Hotstar · 720p"),
            sourceOf("Hotstar", "1080p", "cdn.b.com", "Hotstar · 1080p · 2.1 GB")
        )
        assertEquals(2, matchSource(room, ours)) // exact label
        assertEquals(1, matchSource(sourceOf("Hotstar", "4K", "cdn.a.com", null), ours)) // provider + host
        assertNull(matchSource(sourceOf("castle", "1080p", null, null), ours))
        assertTrue(sameSource(room, ours[2]))
        assertFalse(sameSource(room, ours[0]))
        assertFalse(sameSource(room, null))
    }

    @Test fun clockFormat() {
        assertEquals("1:02:14", clock(3_734_000))
        assertEquals("4:05", clock(245_400))
    }

    @Test fun echoDetection() {
        assertTrue(isEcho("me", "me"))
        assertFalse(isEcho("you", "me"))
        assertFalse(isEcho("me", null))
    }
}
