package it.smartplugmonitor

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Window
import android.widget.Button
import android.widget.TextView

class FineCycleActivity : Activity() {

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        requestWindowFeature(
            Window.FEATURE_NO_TITLE
        )

        setContentView(
            R.layout.activity_fine_cycle
        )

        findViewById<TextView>(
            R.id.finishedText
        ).text = "CICLO TERMINATO"

        findViewById<Button>(
            R.id.okButton
        ).setOnClickListener {

            val intent =
                Intent(
                    this,
                    MonitorService::class.java
                ).apply {
                    action =
                        MonitorService.ACTION_STOP_ALARM
                }

            startService(intent)

            finish()
        }
    }

    override fun onBackPressed() {
        // Il tasto indietro non ferma l'avviso.
    }
}
