package tech.musxi.player.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryDensityTest {

    @Test fun cyclesListThenCompactThenLargeThenList() {
        assertEquals(LibraryDensity.GRID_COMPACT, LibraryDensity.LIST.next())
        assertEquals(LibraryDensity.GRID_LARGE, LibraryDensity.GRID_COMPACT.next())
        assertEquals(LibraryDensity.LIST, LibraryDensity.GRID_LARGE.next())
    }

    @Test fun columnsMatchEachLayout() {
        assertEquals(1, LibraryDensity.LIST.columns)
        assertEquals(3, LibraryDensity.GRID_COMPACT.columns)
        assertEquals(2, LibraryDensity.GRID_LARGE.columns)
    }

    @Test fun missingValueReadsAsList() {
        val store = LibraryDensityStore(prefs())
        assertEquals(LibraryDensity.LIST, store.load())
    }

    @Test fun savedChoiceSurvivesANewStore() {
        val prefs = prefs()
        LibraryDensityStore(prefs).save(LibraryDensity.GRID_LARGE)
        assertEquals(LibraryDensity.GRID_LARGE, LibraryDensityStore(prefs).load())
    }

    @Test fun unknownStoredValueReadsAsList() {
        val prefs = prefs()
        prefs.edit().putString(LibraryDensityStore.KEY, "NO_SUCH_LAYOUT").apply()
        assertEquals(LibraryDensity.LIST, LibraryDensityStore(prefs).load())
    }

    private fun prefs() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("library-density-test", android.content.Context.MODE_PRIVATE)
        .also { it.edit().clear().commit() }
}
