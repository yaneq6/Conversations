package eu.siacs.conversations.feature.xmpp.command

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentSender
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.feature.xmpp.query.ManuallyChangePresence
import eu.siacs.conversations.ui.UiCallback
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class AnnouncePgp @Inject constructor(
    private val activity: XmppActivity,
    private val choosePgpSignId: ChoosePgpSignId,
    private val manuallyChangePresence: ManuallyChangePresence,
    private val displayErrorDialog: DisplayErrorDialog
) {

    operator fun invoke(
        account: Account,
        conversation: Conversation?,
        intent: Intent?,
        onSuccess: Runnable?
    ) {
        if (account.pgpId == 0L) {
            choosePgpSignId(account)
        } else {
            var status: String? = null
            if (manuallyChangePresence()) {
                status = account.presenceStatusMessage
            }
            if (status == null) {
                status = ""
            }
            activity.xmppConnectionService.pgpEngine!!.generateSignature(
                intent,
                account,
                status,
                object : UiCallback<String> {

                    override fun userInputRequried(pi: PendingIntent, signature: String) {
                        try {
                            activity.startIntentSenderForResult(
                                pi.intentSender,
                                XmppActivity.REQUEST_ANNOUNCE_PGP,
                                null,
                                0,
                                0,
                                0
                            )
                        } catch (ignored: IntentSender.SendIntentException) {
                        }

                    }

                    override fun success(signature: String) {
                        account.pgpSignature = signature
                        val xmppConnectionService = activity.xmppConnectionService
                        xmppConnectionService.databaseBackend.updateAccount(account)
                        xmppConnectionService.sendPresence(account)
                        if (conversation != null) {
                            conversation.nextEncryption =
                                Message.ENCRYPTION_PGP
                            xmppConnectionService.updateConversation(conversation)
                        }
                        if (onSuccess != null) {
                            activity.runOnUiThread(onSuccess)
                        }
                    }

                    override fun error(error: Int, signature: String) {
                        if (error == 0) {
                            account.setPgpSignId(0)
                            account.unsetPgpSignature()
                            activity.xmppConnectionService.databaseBackend.updateAccount(account)
                            choosePgpSignId(account)
                        } else {
                            displayErrorDialog(error)
                        }
                    }
                })
        }
    }
}