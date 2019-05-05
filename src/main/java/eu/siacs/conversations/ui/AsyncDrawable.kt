package eu.siacs.conversations.ui

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import eu.siacs.conversations.feature.xmpp.BitmapWorkerTask
import java.lang.ref.WeakReference

class AsyncDrawable constructor(
    res: Resources,
    bitmap: Bitmap?,
    bitmapWorkerTask: BitmapWorkerTask
) : BitmapDrawable(res, bitmap) {

    private val bitmapWorkerTaskReference = WeakReference(bitmapWorkerTask)

    val bitmapWorkerTask: BitmapWorkerTask
        get() = bitmapWorkerTaskReference.get()!!

}