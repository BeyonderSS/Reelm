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

    @Test fun echoDetection() {
        assertTrue(isEcho("me", "me"))
        assertFalse(isEcho("you", "me"))
        assertFalse(isEcho("me", null))
    }
}
