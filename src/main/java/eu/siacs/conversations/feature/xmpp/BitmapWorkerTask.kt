package eu.siacs.conversations.feature.xmpp

import android.graphics.Bitmap
import android.os.AsyncTask
import android.widget.ImageView
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.ui.XmppActivity
import java.io.IOException
import java.lang.ref.WeakReference

class BitmapWorkerTask constructor(imageView: ImageView) :
    AsyncTask<Message, Void, Bitmap>() {
    private val imageViewReference = WeakReference(imageView)
    var message: Message? = null

    override fun doInBackground(vararg params: Message): Bitmap? {
        if (isCancelled) {
            return null
        }
        message = params[0]
        return try {
            val activity = XmppActivity.find(imageViewReference)
            if (activity?.xmppConnectionService != null) {
                activity.xmppConnectionService.fileBackend.getThumbnail(
                    message,
                    (activity.metrics!!.density * 288).toInt(),
                    false
                )
            } else {
                null
            }
        } catch (e: IOException) {
            null
        }

    }

    override fun onPostExecute(bitmap: Bitmap?) {
        if (!isCancelled) {
            val imageView = imageViewReference.get()
            if (imageView != null) {
                imageView.setImageBitmap(bitmap)
                imageView.setBackgroundColor(if (bitmap == null) -0xcccccd else 0x00000000)
            }
        }
    }
}