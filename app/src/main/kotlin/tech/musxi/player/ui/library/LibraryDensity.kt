package tech.musxi.player.ui.library

import android.content.Context
import android.content.SharedPreferences

/**
 * How playlists are laid out on the library tab. The list is the default; the two grids are opt-in
 * through the header button. [columns] is 1 for the list, whose rows keep their 48dp covers and
 * dividers.
 */
enum class LibraryDensity(val columns: Int, val label: String) {
    LIST(1, "列表"),
    GRID_COMPACT(3, "紧凑网格"),
    GRID_LARGE(2, "大网格");

    /** The order the header button walks: list, compact grid, large grid, back to list. */
    fun next(): LibraryDensity = entries[(ordinal + 1) % entries.size]
}

/**
 * Keeps the chosen density on this device, in its own preferences file so it stays apart from the
 * search history. An unknown or missing value reads as [LibraryDensity.LIST].
 *
 * TODO: move this into the app's shared settings store once one exists. Only this class and
 * [libraryDensityPrefs] would change.
 */
class LibraryDensityStore(private val prefs: SharedPreferences) {

    fun load(): LibraryDensity {
        val name = prefs.getString(KEY, null)
        return LibraryDensity.entries.firstOrNull { it.name == name } ?: LibraryDensity.LIST
    }

    fun save(density: LibraryDensity) {
        prefs.edit().putString(KEY, density.name).apply()
    }

    internal companion object {
        const val KEY = "density"
    }
}

/** The preferences file behind [LibraryDensityStore]. */
fun libraryDensityPrefs(context: Context): SharedPreferences =
    context.getSharedPreferences("library-density-v1", Context.MODE_PRIVATE)
