package eu.siacs.conversations.feature.xmpp.command

import android.app.PendingIntent
import android.content.IntentSender
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.feature.xmpp.XmppConst
import eu.siacs.conversations.ui.UiCallback
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class ChoosePgpSignId @Inject constructor(
    private val activity: XmppActivity

) {
    operator fun invoke(account: Account) {
        activity.xmppConnectionService.pgpEngine!!.chooseKey(
            account,
            object : UiCallback<Account> {
                override fun success(account1: Account) {}

                override fun error(errorCode: Int, `object`: Account) {

                }

                override fun userInputRequried(pi: PendingIntent, `object`: Account) {
                    try {
                        activity.startIntentSenderForResult(
                            pi.intentSender,
                            XmppConst.REQUEST_CHOOSE_PGP_ID, null, 0, 0, 0
                        )
                    } catch (ignored: IntentSender.SendIntentException) {
                    }

                }
            })
    }
}