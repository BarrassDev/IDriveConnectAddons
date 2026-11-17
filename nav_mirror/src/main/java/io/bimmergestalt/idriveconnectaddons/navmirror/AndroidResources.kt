package io.bimmergestalt.idriveconnectaddons.navmirror

import android.content.Context

class AndroidResources(val context: Context) {
    fun getRaw(id: Int): ByteArray {
        return context.resources.openRawResource(id).readBytes()
    }
}
