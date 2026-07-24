package it.gvasta.gpstracker

import android.app.Application

/**
 * Classe Application: e' il primo codice eseguito all'avvio del processo.
 * Qui installiamo il gestore globale dei crash, cosi' copre ogni componente
 * (activity, servizio, receiver, worker).
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        FileLogger.log(this, "App", "Processo avviato")
    }
}
