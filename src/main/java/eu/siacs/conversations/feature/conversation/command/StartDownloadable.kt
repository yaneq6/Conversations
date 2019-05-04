package eu.siacs.conversations.feature.conversation.command

import android.Manifest
import android.app.Activity
import android.widget.Toast
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.entities.TransferablePlaceholder
import eu.siacs.conversations.feature.conversation.REQUEST_START_DOWNLOAD
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import timber.log.Timber
import javax.inject.Inject

@ActivityScope
class StartDownloadable @Inject constructor(
    private val activity: Activity,
    private val fragment: ConversationFragment,
    private val hasPermissions: HasPermissions,
    private val createNewConnection: CreateNewConnection
) : (Message?) -> Unit {
    override fun invoke(message: Message?) {
        if (!hasPermissions(
                REQUEST_START_DOWNLOAD,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        ) {
            fragment.pendingDownloadableMessage = message
            return
        }
        val transferable = message!!.transferable
        if (transferable != null) {
            if (transferable is TransferablePlaceholder && message.hasFileOnRemoteHost()) {
                createNewConnection(message)
                return
            }
            if (!transferable.start()) {
                Timber.d("type: ${transferable.javaClass.name}")
                Toast.makeText(
                    activity,
                    R.string.not_connected_try_again,
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else if (message.treatAsDownloadable() || message.hasFileOnRemoteHost()) {
            createNewConnection(message)
        }
    }
}