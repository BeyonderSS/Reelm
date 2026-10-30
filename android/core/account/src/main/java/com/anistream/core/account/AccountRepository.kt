package com.anistream.core.account

import android.content.Context
import com.anistream.core.database.ActiveProfile
import com.anistream.domain.KidsPolicy
import com.anistream.domain.MaturePolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

data class PairCode(val code: String, val expiresAt: Long)

/**
 * Account state for the whole app. Token, user, profile cache and active profile live in plain SharedPreferences
 * (ponytail: not encrypted; EncryptedSharedPreferences isn't a dependency and the token only opens this user's watch history).
 * When [enabled] is false (blank REELM_API_URL) nothing here is ever reached and the app stays local-only.
 */
@Singleton
class AccountRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val api: AccountApi
) {
    val enabled: Boolean get() = api.enabled
    val baseUrl: String get() = api.baseUrl

    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)

    private val _isAdmin = MutableStateFlow(prefs.getBoolean("isAdmin", false))
    /** `is_admin` from /v1/me, cached so the entry shows before the network answers. Never true for guests. */
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()

    private val _adminMode = MutableStateFlow(prefs.getBoolean("adminMode", false))
    /** The "Admin mode" switch; only meaningful while [isAdmin]. */
    val adminMode: StateFlow<Boolean> = _adminMode.asStateFlow()
    fun setAdminMode(on: Boolean) { _adminMode.value = on; prefs.edit().putBoolean("adminMode", on).apply() }

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    /**
     * The launch gate ("Who's watching?", or Welcome when signed out) is showing. It opens on first launch, after sign out
     * and from the profile button; it closes once a profile or guest mode is chosen, and that choice is remembered so the
     * next launch goes straight home.
     */
    private val _gate = MutableStateFlow(false)
    val gate: StateFlow<Boolean> = _gate.asStateFlow()

    private val guest: Boolean get() = prefs.getBoolean("guest", false)

    /** True when there is something to go back to: an active profile, or guest mode while signed out. */
    val hasChoice: Boolean get() = _session.value?.activeProfile != null || (_session.value == null && guest)

    val token: String? get() = _session.value?.token
    /** one id per install, shown to the user's other devices in the hub */
    val deviceId: String = prefs.getString("deviceId", null) ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("deviceId", it).apply() }

    init {
        val token = prefs.getString("token", null)
        val user = prefs.getString("user", null)?.let { runCatching { Json.parseToJsonElement(it).jsonObject.toUser() }.getOrNull() }
        if (enabled && token != null && user != null) {
            _profiles.value = prefs.getString("profiles", null)?.let { runCatching { Json.parseToJsonElement(it).jsonArray.map { p -> p.jsonObject.toProfile() } }.getOrNull() }.orEmpty()
            publish(token, user, prefs.getString("activeProfile", null))
        }
        _gate.value = enabled && !hasChoice
    }

    private fun publish(token: String, user: User, activeId: String?) {
        val active = _profiles.value.firstOrNull { it.id == activeId }
        _session.value = Session(token, user, active)
        ActiveProfile.id = active?.id ?: ""
        KidsPolicy.active = active?.kids == true
        MaturePolicy.active = active?.mature != true
    }

    suspend fun login(username: String, password: String) = auth("login", username, password)
    suspend fun register(username: String, password: String) = auth("register", username, password)

    private suspend fun auth(path: String, username: String, password: String) {
        val res = api.call("POST", "/v1/auth/$path", buildJsonObject { put("username", username.trim().lowercase()); put("password", password) })!!
        signIn(str(res, "token"), res.userObj(), pickSingle = path == "login")
    }

    /**
     * Also the TV path: a claimed pairing code hands back the same token + user. With [pickSingle] a lone profile is
     * picked at once; sign-up passes false so it can name that profile first.
     */
    suspend fun signIn(token: String, user: User, pickSingle: Boolean = true) {
        prefs.edit().putString("token", token).remove("guest").putString("user", buildJsonObject { put("id", user.id); put("username", user.username) }.toString()).remove("activeProfile").apply()
        _profiles.value = emptyList()
        publish(token, user, null)
        refreshProfiles()
        // a single profile needs no picker; with several the user picks
        if (pickSingle && _profiles.value.size == 1) selectProfile(_profiles.value[0].id)
    }

    suspend fun logout() {
        token?.let { runCatching { api.call("POST", "/v1/auth/logout", token = it) } }
        prefs.edit().clear().putString("deviceId", deviceId).apply()
        _profiles.value = emptyList()
        _session.value = null
        _isAdmin.value = false
        _adminMode.value = false
        ActiveProfile.id = ""
        KidsPolicy.active = false
        MaturePolicy.active = true
        _gate.value = true
    }

    fun continueAsGuest() {
        prefs.edit().putBoolean("guest", true).apply()
        _gate.value = false
    }

    fun openGate() { _gate.value = true }

    /** Back out of the gate when there is a remembered choice to return to. */
    fun closeGate(): Boolean = hasChoice.also { if (it) _gate.value = false }

    suspend fun refreshProfiles() {
        val t = token ?: return
        val list = api.call("GET", "/v1/profiles", token = t)!!["profiles"]!!.jsonArray.map { it.jsonObject.toProfile() }
        setProfiles(list)
        runCatching { api.call("GET", "/v1/me", token = t)!!["user"]!!.jsonObject["is_admin"]?.jsonPrimitive?.booleanOrNull }.getOrNull()?.let {
            _isAdmin.value = it
            prefs.edit().putBoolean("isAdmin", it).apply()
        }
    }

    private fun setProfiles(list: List<Profile>) {
        _profiles.value = list
        prefs.edit().putString("profiles", JsonArray(list.map { buildJsonObject { put("id", it.id); put("name", it.name); put("avatar", it.avatar); put("kids", it.kids); put("mature", it.mature) } }).toString()).apply()
        val s = _session.value ?: return
        publish(s.token, s.user, s.activeProfile?.id)
    }

    fun selectProfile(id: String) {
        val s = _session.value ?: return
        prefs.edit().putString("activeProfile", id).apply()
        publish(s.token, s.user, id)
        _gate.value = false
    }

    suspend fun createProfile(name: String, avatar: String, kids: Boolean): Profile {
        val p = api.call("POST", "/v1/profiles", buildJsonObject { put("name", name.trim()); put("avatar", avatar); put("kids", kids) }, token)!!.toProfile()
        setProfiles(_profiles.value + p)
        return p
    }

    suspend fun updateProfile(id: String, name: String, avatar: String, kids: Boolean, mature: Boolean = false) {
        val p = api.call("PATCH", "/v1/profiles/$id", buildJsonObject { put("name", name.trim()); put("avatar", avatar); put("kids", kids); put("mature", mature && !kids) }, token)!!.toProfile()
        setProfiles(_profiles.value.map { if (it.id == id) p else it })
    }

    suspend fun deleteProfile(id: String) {
        api.call("DELETE", "/v1/profiles/$id", token = token)
        val rest = _profiles.value.filter { it.id != id }
        setProfiles(rest)
    }

    // --- TV pairing (device-code flow) ---

    /** TV, signed out: asks the server for a code to show as a QR. */
    suspend fun startPair(): PairCode {
        val r = api.call("POST", "/v1/pair")!!
        return PairCode(str(r, "code"), r["expiresAt"]?.toString()?.toLongOrNull() ?: 0L)
    }

    /** TV: one poll. Signs in when the phone has claimed the code; true = signed in. */
    suspend fun pollPair(code: String): Boolean {
        val r = api.call("GET", "/v1/pair/$code")!!
        if (str(r, "status") != "claimed") return false
        signIn(str(r, "token"), r.userObj())
        return true
    }

    /** Phone, signed in: approves the TV that shows [code]. */
    suspend fun claimPair(code: String) {
        api.call("POST", "/v1/pair/${code.trim().uppercase()}/claim", token = token)
    }
}
