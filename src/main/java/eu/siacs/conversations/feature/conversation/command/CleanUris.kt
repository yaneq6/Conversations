package eu.siacs.conversations.feature.conversation.command

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import eu.siacs.conversations.R
import eu.siacs.conversations.persistance.FileBackend
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class CleanUris @Inject constructor(
    private val activity: Activity
) : (MutableList<Uri>) -> List<Uri> {
    override fun invoke(uris: MutableList<Uri>): List<Uri> {
        val iterator = uris.iterator()
        while (iterator.hasNext()) {
            val uri = iterator.next()
            if (FileBackend.weOwnFile(activity, uri)) {
                iterator.remove()
                Toast.makeText(
                    activity,
                    R.string.security_violation_not_attaching_file,
                    Toast.LENGTH_SHORT
                )
                    .show()
            }
        }
        return uris
    }
}