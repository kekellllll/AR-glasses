package com.ultronai.productarglasses

import android.app.Application
import com.ffalcon.mercury.android.sdk.MercurySDK

class ProductARGlassesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MercurySDK.init(this)
    }
}