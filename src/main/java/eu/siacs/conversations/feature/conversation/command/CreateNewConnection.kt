package eu.siacs.conversations.feature.conversation.command

import android.widget.Toast
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class CreateNewConnection @Inject constructor(
    private val activity: XmppActivity
) : (Message) -> Unit {

    override fun invoke(message: Message) = activity.xmppConnectionService.httpConnectionManager.run {
        if (!checkConnection(message)) {
            Toast.makeText(
                activity,
                R.string.not_connected_try_again,
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        createNewDownloadConnection(message, true)
    }

}