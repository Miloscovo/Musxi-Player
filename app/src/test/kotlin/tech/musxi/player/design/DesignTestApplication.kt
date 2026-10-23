package tech.musxi.player.design

import android.app.Application

/**
 * Application for Robolectric screenshot tests. The real [tech.musxi.player.MusxiApplication]
 * binds a MediaController to the playback service in its field initialisers, which Robolectric
 * cannot resolve (it hands back a null ComponentName). Screens are pure functions of state, so
 * design tests only need a bare Application.
 */
class DesignTestApplication : Application()
