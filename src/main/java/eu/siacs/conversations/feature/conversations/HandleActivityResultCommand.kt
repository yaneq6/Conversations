package eu.siacs.conversations.feature.conversations

import android.app.Activity
import eu.siacs.conversations.feature.conversation.REQUEST_DECRYPT_PGP
import eu.siacs.conversations.feature.conversation.getConversationReliable
import eu.siacs.conversations.ui.ConversationsActivity
import eu.siacs.conversations.ui.XmppActivity
import eu.siacs.conversations.ui.util.ActivityResult
import io.aakit.scope.ActivityScope
import org.openintents.openpgp.util.OpenPgpApi
import timber.log.Timber
import javax.inject.Inject

@ActivityScope
class HandleActivityResultCommand @Inject constructor(
    private val activity: ConversationsActivity
) : (ActivityResult) -> Unit {

    override fun invoke(activityResult: ActivityResult): Unit = activityResult.run {
        if (resultCode == Activity.RESULT_OK) getConversationReliable(
            activity
        )?.run {
            when (requestCode) {
                REQUEST_DECRYPT_PGP -> account.pgpDecryptionService.continueDecryption(data)
                XmppActivity.REQUEST_CHOOSE_PGP_ID -> {
                    val id = data.getLongExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, 0)
                    if (id != 0L) {
                        account.setPgpSignId(id)
                        activity.announcePgp(account, null, null, activity.onOpenPGPKeyPublished)
                    } else {
                        activity.choosePgpSignId(account)
                    }
                }
                XmppActivity.REQUEST_ANNOUNCE_PGP -> activity.announcePgp(
                    account,
                    this,
                    data,
                    activity.onOpenPGPKeyPublished
                )
            }
        } ?: Timber.d("conversation not found")
        else when (requestCode) {
            REQUEST_DECRYPT_PGP -> getConversationReliable(
                activity
            )
                ?.account
                ?.pgpDecryptionService
                ?.giveUpCurrentDecryption()

            XmppActivity.REQUEST_BATTERY_OP -> activity.setNeverAskForBatteryOptimizationsAgain()
        }
    }
}