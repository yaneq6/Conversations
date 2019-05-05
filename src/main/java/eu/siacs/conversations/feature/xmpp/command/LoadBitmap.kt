package eu.siacs.conversations.feature.xmpp.command

import android.content.res.Resources
import android.graphics.Bitmap
import android.widget.ImageView
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.ui.*
import io.aakit.scope.ActivityScope
import java.io.IOException
import java.util.concurrent.RejectedExecutionException
import javax.inject.Inject


@ActivityScope
class LoadBitmap @Inject constructor(
    private val activity: XmppActivity,
    private val resources: Resources
) {
    operator fun invoke(message: Message, imageView: ImageView) {
        var bm: Bitmap?
        try {
            bm = activity.xmppConnectionService.fileBackend.getThumbnail(
                message,
                (activity.metrics!!.density * 288).toInt(),
                true
            )
        } catch (e: IOException) {
            bm = null
        }

        if (bm != null) {
            XmppActivity.cancelPotentialWork(message, imageView)
            imageView.setImageBitmap(bm)
            imageView.setBackgroundColor(0x00000000)
        } else {
            if (XmppActivity.cancelPotentialWork(message, imageView)) {
                imageView.setBackgroundColor(-0xcccccd)
                imageView.setImageDrawable(null)
                val task = XmppActivity.BitmapWorkerTask(imageView)
                val asyncDrawable = XmppActivity.AsyncDrawable(
                    resources, null, task
                )
                imageView.setImageDrawable(asyncDrawable)
                try {
                    task.execute(message)
                } catch (ignored: RejectedExecutionException) {
                    ignored.printStackTrace()
                }
            }
        }
    }
}