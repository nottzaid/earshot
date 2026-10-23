package dev.nottzaid.earshot.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.store by preferencesDataStore("earshot")

data class Prefs(
    val gainDb: Float = 0f,
    val levelControl: Boolean = false, // off: the mic's own level (0 dB) and no slider
    val echoCancel: Boolean = false,   // the PC subtracts what its own speakers put into the mic
    val micKey: String? = null,
    val smartStandby: Boolean = true,
    val defaultMic: Boolean = true,
    val muted: Boolean = false,
    val askedBackground: Boolean = false, // asked once to run unrestricted; afterwards it's a switch in settings
    val deviceId: String = "",
    val pcHost: String? = null,      // last address of the paired PC (a hint; identity is pcId)
    val pcId: String? = null,        // the paired PC's stable id
    val pcName: String? = null,
    val pcSecret: String? = null,    // pairing secret shared with that PC
) {
    val paired get() = pcId != null && pcSecret != null
    val effectiveGainDb get() = if (levelControl) gainDb else 0f
}

class Settings(private val context: Context) {
    private object K {
        val gain = floatPreferencesKey("gain_db")
        val levelControl = booleanPreferencesKey("level_control")
        val echoCancel = booleanPreferencesKey("echo_cancel")
        val mic = stringPreferencesKey("mic")
        val standby = booleanPreferencesKey("smart_standby")
        val defaultMic = booleanPreferencesKey("default_mic")
        val muted = booleanPreferencesKey("muted")
        val askedBackground = booleanPreferencesKey("asked_background")
        val deviceId = stringPreferencesKey("device_id")
        val pcHost = stringPreferencesKey("pc_host")
        val pcId = stringPreferencesKey("pc_id")
        val pcName = stringPreferencesKey("pc_name")
        val pcSecret = stringPreferencesKey("pc_secret")
    }

    val prefs: Flow<Prefs> = context.store.data.map { p -> p.toPrefs() }

    private fun Preferences.toPrefs() = Prefs(
        gainDb = this[K.gain] ?: 0f,
        levelControl = this[K.levelControl] ?: false,
        echoCancel = this[K.echoCancel] ?: false,
        micKey = this[K.mic],
        smartStandby = this[K.standby] ?: true,
        defaultMic = this[K.defaultMic] ?: true,
        muted = this[K.muted] ?: false,
        askedBackground = this[K.askedBackground] ?: false,
        deviceId = this[K.deviceId].orEmpty(),
        pcHost = this[K.pcHost],
        pcId = this[K.pcId],
        pcName = this[K.pcName],
        pcSecret = this[K.pcSecret],
    )

    /** The install's stable id, created on first use. */
    suspend fun deviceId(): String {
        var id = ""
        context.store.edit { p ->
            id = p[K.deviceId] ?: UUID.randomUUID().toString().also { p[K.deviceId] = it }
        }
        return id
    }

    suspend fun setGain(db: Float) = context.store.edit { it[K.gain] = db }
    suspend fun setLevelControl(on: Boolean) = context.store.edit { it[K.levelControl] = on }
    suspend fun setEchoCancel(on: Boolean) = context.store.edit { it[K.echoCancel] = on }
    suspend fun setMic(key: String) = context.store.edit { it[K.mic] = key }
    suspend fun setSmartStandby(on: Boolean) = context.store.edit { it[K.standby] = on }
    suspend fun setDefaultMic(on: Boolean) = context.store.edit { it[K.defaultMic] = on }
    suspend fun setMuted(on: Boolean) = context.store.edit { it[K.muted] = on }
    suspend fun setAskedBackground() = context.store.edit { it[K.askedBackground] = true }

    suspend fun rememberPc(host: String, id: String, name: String, secret: String?) = context.store.edit {
        it[K.pcHost] = host
        it[K.pcId] = id
        it[K.pcName] = name
        if (secret != null) it[K.pcSecret] = secret
    }

    suspend fun forgetPc() = context.store.edit {
        it.remove(K.pcHost)
        it.remove(K.pcId)
        it.remove(K.pcName)
        it.remove(K.pcSecret)
    }
}
