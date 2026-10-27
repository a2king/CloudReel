package com.yingshicang.tv

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.yingshicang.tv.core.database.AppDatabase
import com.yingshicang.tv.core.lan.LanPortal
import com.yingshicang.tv.core.network.AppHttp
import com.yingshicang.tv.core.security.SecretBox
import com.yingshicang.tv.data.MediaRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class YingShiApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val prefs = AppPrefs(appContext)
    private val secretBox = SecretBox()
    private val database = Room.databaseBuilder(appContext, AppDatabase::class.java, "yingshi.db")
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
    val repository = MediaRepository(appContext, database.dao(), AppHttp(), secretBox, scope)

    fun pan115Cookie(): String = cloudSecret("115")

    fun savePan115Cookie(cookie: String) = saveCloudSecret("115", cookie)

    fun cloudSecret(kind: String): String {
        return try {
            secretBox.decrypt(prefs.cloudCipher(kind))
        } catch (_: Exception) {
            ""
        }
    }

    fun saveCloudSecret(kind: String, value: String) {
        val clean = value.trim().removePrefix("Cookie:").trim()
        prefs.setCloudCipher(kind, if (clean.isEmpty()) "" else secretBox.encrypt(clean))
    }
    val portal = LanPortal { body -> runBlocking { repository.importSubmission(body) } }

    fun start() {
        repository.start()
    }
}

class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("yingshi_prefs", Context.MODE_PRIVATE)

    fun hasSeenWelcome(): Boolean = prefs.getBoolean(KEY_WELCOME, false)

    fun setSeenWelcome() {
        prefs.edit().putBoolean(KEY_WELCOME, true).apply()
    }

    fun cloudCipher(kind: String): String {
        val key = if (kind == "115") KEY_PAN115 else "cloud_$kind"
        return prefs.getString(key, "").orEmpty()
    }

    fun setCloudCipher(kind: String, value: String) {
        val key = if (kind == "115") KEY_PAN115 else "cloud_$kind"
        prefs.edit().putString(key, value).apply()
    }

    fun homeSourceId(): String = prefs.getString(KEY_HOME_SOURCE, "").orEmpty()

    fun setHomeSourceId(value: String) {
        prefs.edit().putString(KEY_HOME_SOURCE, value).apply()
    }

    private companion object {
        const val KEY_WELCOME = "seen_welcome"
        const val KEY_PAN115 = "pan115_cookie"
        const val KEY_HOME_SOURCE = "home_source_id"
    }
}
