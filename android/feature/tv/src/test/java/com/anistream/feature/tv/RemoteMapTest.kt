package com.anistream.feature.tv

import android.view.KeyEvent
import com.anistream.feature.player.SubPosition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteMapTest {
    private fun j(s: String) = Json.parseToJsonElement(s)

    @Test fun subtitleSizeAndPosition() {
        assertEquals("l", RemoteMap.sizeCode(1.3f))
        assertEquals(1.6f, RemoteMap.scale(JsonPrimitive("xl")))
        assertNull(RemoteMap.scale(JsonPrimitive("huge")))
        assertEquals("raised", RemoteMap.posCode(SubPosition.RAISED))
        assertEquals(SubPosition.TOP, RemoteMap.position(JsonPrimitive("top")))
        assertNull(RemoteMap.position(JsonNull))
    }

    @Test fun tracksEpisodesSpeeds() {
        assertEquals(3, RemoteMap.track(j("""{"track":"3"}""")))
        assertEquals(-1, RemoteMap.track(j("""{"track":null}""")))
        assertNull(RemoteMap.track(j("""{}""")))
        assertEquals(2 to 5, RemoteMap.episode(j("""{"season":2,"episode":5}""")))
        assertNull(RemoteMap.episode(j("""{"season":2}""")))
        assertEquals(1.25f, RemoteMap.speed(125))
        assertEquals(2f, RemoteMap.speed(400))
        assertEquals(125, RemoteMap.percent(1.25f))
        assertEquals(1080, RemoteMap.heightOf("1080p HD"))
        assertEquals(0, RemoteMap.heightOf("default"))
    }

    @Test fun keys() {
        assertEquals(KeyEvent.KEYCODE_DPAD_CENTER, remoteKeyCode("ok"))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, remoteKeyCode("playpause"))
        assertNull(remoteKeyCode("home"))
    }
}
