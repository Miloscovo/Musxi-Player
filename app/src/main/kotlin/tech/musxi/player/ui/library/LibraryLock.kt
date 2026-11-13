package tech.musxi.player.ui.library

import android.content.Context
import android.content.SharedPreferences
import tech.musxi.core.account.Platform

/**
 * 锁定平台: for someone who only uses one source. The library opens on it, and a sideways swipe on
 * the page walks the tabs (曲库 ↔ 搜索 ↔ 我的) instead of the platforms. The segments stay, so a tap
 * still shows another platform for a moment.
 */
enum class LibraryLock(val label: String, val platform: Platform?) {
    NONE("不锁定", null),
    NETEASE("网易云", Platform.NETEASE),
    QQ("QQ音乐", Platform.QQ),
    KUGOU("酷狗", Platform.KUGOU),
    LOCAL(LOCAL_SEGMENT, null),
}

/** Kept on this device only, like the library density; an unknown value reads as [LibraryLock.NONE]. */
class LibraryLockStore(private val prefs: SharedPreferences) {
    fun load(): LibraryLock {
        val name = prefs.getString(KEY, null)
        return LibraryLock.entries.firstOrNull { it.name == name } ?: LibraryLock.NONE
    }

    fun save(lock: LibraryLock) {
        prefs.edit().putString(KEY, lock.name).apply()
    }

    internal companion object {
        const val KEY = "lock"
    }
}

fun libraryLockPrefs(context: Context): SharedPreferences =
    context.getSharedPreferences("library-lock-v1", Context.MODE_PRIVATE)
