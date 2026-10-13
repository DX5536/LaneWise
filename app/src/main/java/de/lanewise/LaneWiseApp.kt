package de.lanewise

import android.app.Application
import org.maplibre.android.MapLibre

class LaneWiseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
    }
}
