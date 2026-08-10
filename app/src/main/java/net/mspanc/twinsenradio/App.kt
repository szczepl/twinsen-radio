package net.mspanc.twinsenradio

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.mspanc.twinsenradio.data.StationRepository

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // Listy M3U uzytkownika dociagamy w tle, zeby pierwsze wejscie w Android Auto
        // nie czekalo na siec - wbudowana lista jest dostepna od razu.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            StationRepository.get(this@App).refreshUserLists()
        }
    }
}
