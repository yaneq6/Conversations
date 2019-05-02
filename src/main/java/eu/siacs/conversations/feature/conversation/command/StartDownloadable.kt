package eu.siacs.conversations.feature.conversation.command

import android.Manifest
import android.util.Log
import android.widget.Toast
import eu.siacs.conversations.Config
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.entities.TransferablePlaceholder
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class StartDownloadable @Inject constructor(
    private val fragment: ConversationFragment
) : (Message?) -> Unit {
    override fun invoke(message: Message?) = fragment.run {
        if (!hasPermissions(
                ConversationFragment.REQUEST_START_DOWNLOAD,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        ) {
            this.mPendingDownloadableMessage = message
            return
        }
        val transferable = message!!.transferable
        if (transferable != null) {
            if (transferable is TransferablePlaceholder && message.hasFileOnRemoteHost()) {
                createNewConnection(message)
                return
            }
            if (!transferable.start()) {
                Log.d(Config.LOGTAG, "type: " + transferable.javaClass.name)
                Toast.makeText(
                    getActivity(),
                    R.string.not_connected_try_again,
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else if (message.treatAsDownloadable() || message.hasFileOnRemoteHost()) {
            createNewConnection(message)
        }
    }
}