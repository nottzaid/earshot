package dev.nottzaid.earshot.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.nottzaid.earshot.audio.LatencyMode
import dev.nottzaid.earshot.audio.SoundMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.store by preferencesDataStore("earshot")

data class Prefs(
    val gainDb: Float = 0f,
    val sound: SoundMode = SoundMode.Studio,
    val latency: LatencyMode = LatencyMode.Balanced,
    val micKey: String? = null,
    val rumbleFilter: Boolean = false,
    val smartStandby: Boolean = true,
    val defaultMic: Boolean = true,
    val muted: Boolean = false,
    val deviceId: String = "",
    val pcHost: String? = null,      // last PC address that answered
    val pcName: String? = null,
    val pcSecret: String? = null,    // pairing secret issued by that PC
)

class Settings(private val context: Context) {
    private object K {
        val gain = floatPreferencesKey("gain_db")
        val sound = stringPreferencesKey("sound")
        val latency = stringPreferencesKey("latency")
        val mic = stringPreferencesKey("mic")
        val rumble = booleanPreferencesKey("rumble")
        val standby = booleanPreferencesKey("smart_standby")
        val defaultMic = booleanPreferencesKey("default_mic")
        val muted = booleanPreferencesKey("muted")
        val deviceId = stringPreferencesKey("device_id")
        val pcHost = stringPreferencesKey("pc_host")
        val pcName = stringPreferencesKey("pc_name")
        val pcSecret = stringPreferencesKey("pc_secret")
    }

    val prefs: Flow<Prefs> = context.store.data.map { p -> p.toPrefs() }

    private fun Preferences.toPrefs() = Prefs(
        gainDb = this[K.gain] ?: 0f,
        sound = SoundMode.entries.firstOrNull { it.name == this[K.sound] } ?: SoundMode.Studio,
        latency = LatencyMode.entries.firstOrNull { it.key == this[K.latency] } ?: LatencyMode.Balanced,
        micKey = this[K.mic],
        rumbleFilter = this[K.rumble] ?: false,
        smartStandby = this[K.standby] ?: true,
        defaultMic = this[K.defaultMic] ?: true,
        muted = this[K.muted] ?: false,
        deviceId = this[K.deviceId].orEmpty(),
        pcHost = this[K.pcHost],
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
    suspend fun setSound(m: SoundMode) = context.store.edit { it[K.sound] = m.name }
    suspend fun setLatency(m: LatencyMode) = context.store.edit { it[K.latency] = m.key }
    suspend fun setMic(key: String) = context.store.edit { it[K.mic] = key }
    suspend fun setRumble(on: Boolean) = context.store.edit { it[K.rumble] = on }
    suspend fun setSmartStandby(on: Boolean) = context.store.edit { it[K.standby] = on }
    suspend fun setDefaultMic(on: Boolean) = context.store.edit { it[K.defaultMic] = on }
    suspend fun setMuted(on: Boolean) = context.store.edit { it[K.muted] = on }

    suspend fun rememberPc(host: String, name: String, secret: String) = context.store.edit {
        it[K.pcHost] = host
        it[K.pcName] = name
        it[K.pcSecret] = secret
    }

    suspend fun forgetPc() = context.store.edit {
        it.remove(K.pcHost)
        it.remove(K.pcName)
        it.remove(K.pcSecret)
    }
}
