package com.anistream.core.account.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anistream.core.account.AccountRepository
import com.anistream.core.account.AuthRules
import com.anistream.core.account.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import dagger.hilt.android.lifecycle.HiltViewModel

data class AccountUi(val busy: Boolean = false, val error: String? = null, val message: String? = null)

/** Shared by the launch gate (welcome, sign in, sign up, who's watching, profile editor) and the account screen. */
@HiltViewModel
class AccountViewModel @Inject constructor(val repo: AccountRepository, private val history: com.anistream.domain.repository.WatchHistoryRepository) : ViewModel() {
    /** tombstones every row of the active profile; HistorySync pushes them (guests stay local) */
    fun clearHistory() { viewModelScope.launch { history.clear() } }

    val session = repo.session
    val profiles = repo.profiles
    val gate = repo.gate

    private val _ui = MutableStateFlow(AccountUi())
    val ui: StateFlow<AccountUi> = _ui.asStateFlow()

    /** Runs [block] with a busy flag; [then] runs on success, a failure becomes a plain-language [AccountUi.error]. */
    private fun run(done: String? = null, signingUp: Boolean = false, then: () -> Unit = {}, block: suspend () -> Unit) {
        if (_ui.value.busy) return
        _ui.value = AccountUi(busy = true)
        viewModelScope.launch {
            _ui.value = try {
                block(); then(); AccountUi(message = done)
            } catch (e: Exception) {
                AccountUi(error = AuthRules.friendly(e, signingUp))
            }
        }
    }

    fun signIn(username: String, password: String) = run { repo.login(username, password) }
    fun register(username: String, password: String, then: () -> Unit) = run(signingUp = true, then = then) { repo.register(username, password) }

    /** Last sign-up step: the server made one profile named after the user; give it the chosen name and avatar and enter. */
    fun finishSignUp(name: String, avatar: String) = run {
        val first = repo.profiles.value.firstOrNull()
        val id = if (first != null) repo.updateProfile(first.id, name, avatar, false).let { first.id } else repo.createProfile(name, avatar, false).id
        repo.selectProfile(id)
    }

    fun guest() = repo.continueAsGuest()
    fun signOut() = run { repo.logout() }
    fun select(p: Profile) = repo.selectProfile(p.id)
    fun refresh() = viewModelScope.launch { runCatching { repo.refreshProfiles() } }
    fun claim(code: String) = run("Your TV is signed in") { repo.claimPair(code) }
    fun save(id: String?, name: String, avatar: String, kids: Boolean, mature: Boolean = false, then: () -> Unit) =
        run(then = then) { if (id == null) repo.createProfile(name, avatar, kids).also { if (mature && !kids) repo.updateProfile(it.id, name, avatar, kids, true) } else repo.updateProfile(id, name, avatar, kids, mature) }
    fun delete(id: String, then: () -> Unit) = run(then = then) { repo.deleteProfile(id) }
    fun clearStatus() { _ui.value = AccountUi() }
}
