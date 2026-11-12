package com.offlinepdf.studio

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class OfflinePdfApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize PDFBox-Android resource loader (loads font assets, glyph maps)
        PDFBoxResourceLoader.init(applicationContext)
    }
}
