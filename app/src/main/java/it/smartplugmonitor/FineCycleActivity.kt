package it.smartplugmonitor

import android.app.Activity
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

            sendBroadcast(
                android.content.Intent(
                    MonitorService.ACTION_UPDATE
                ).apply {
                    setPackage(packageName)
                    putExtra(
                        "stop_alarm",
                        true
                    )
                }
            )

            finish()
        }
    }

    override fun onBackPressed() {
        // Il tasto indietro non deve fermare l'avviso.
    }
}
