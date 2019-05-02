package eu.siacs.conversations.feature.conversation.command

import android.content.Intent
import eu.siacs.conversations.crypto.axolotl.FingerprintStatus
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.TrustKeysActivity
import eu.siacs.conversations.ui.XmppActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class TrustKeysIfNeeded @Inject constructor(
    private val fragment: ConversationFragment
) : (Int) -> Boolean {
    override fun invoke(requestCode: Int): Boolean = fragment.run {
        val axolotlService = conversation!!.account.axolotlService
        val targets = axolotlService.getCryptoTargets(conversation)
        val hasUnaccepted = !conversation!!.acceptedCryptoTargets.containsAll(targets)
        val hasUndecidedOwn = !axolotlService.getKeysWithTrust(FingerprintStatus.createActiveUndecided()).isEmpty()
        val hasUndecidedContacts =
            !axolotlService.getKeysWithTrust(FingerprintStatus.createActiveUndecided(), targets).isEmpty()
        val hasPendingKeys = !axolotlService.findDevicesWithoutSession(conversation).isEmpty()
        val hasNoTrustedKeys = axolotlService.anyTargetHasNoTrustedKeys(targets)
        val downloadInProgress = axolotlService.hasPendingKeyFetches(targets)
        if (hasUndecidedOwn || hasUndecidedContacts || hasPendingKeys || hasNoTrustedKeys || hasUnaccepted || downloadInProgress) {
            axolotlService.createSessionsIfNeeded(conversation)
            val intent = Intent(getActivity(), TrustKeysActivity::class.java)
            val contacts = arrayOfNulls<String>(targets.size)
            for (i in contacts.indices) {
                contacts[i] = targets[i].toString()
            }
            intent.putExtra("contacts", contacts)
            intent.putExtra(XmppActivity.EXTRA_ACCOUNT, conversation!!.account.jid.asBareJid().toString())
            intent.putExtra("conversation", conversation!!.uuid)
            startActivityForResult(intent, requestCode)
            return true
        } else {
            return false
        }
    }
}