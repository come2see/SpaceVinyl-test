package tech.illusion.spacevinyl.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import tech.illusion.spacevinyl.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
