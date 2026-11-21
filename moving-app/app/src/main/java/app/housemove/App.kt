package app.housemove

import android.app.Application
import app.housemove.data.Store

class App : Application() {
    val store: Store by lazy { Store(filesDir) }
}
