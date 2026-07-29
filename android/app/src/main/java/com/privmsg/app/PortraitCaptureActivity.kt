package com.privmsg.app

import android.os.Bundle
import android.view.WindowManager
import android.widget.ImageButton
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/**
 * Escáner en vertical con visor cuadrado (estilo WhatsApp) y botón de volver.
 * Sustituye la CaptureActivity de zxing-embedded, que viene en horizontal.
 */
class PortraitCaptureActivity : CaptureActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Si el usuario tiene el bloqueo de capturas activo, también aquí:
        // el QR escaneado es la clave pública de un contacto.
        if (ChatPrefs(this).screenSecurity()) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        super.onCreate(savedInstanceState)
    }

    override fun initializeContent(): DecoratedBarcodeView {
        setContentView(R.layout.capture_portrait)
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        return findViewById(R.id.zxing_barcode_scanner)
    }
}
