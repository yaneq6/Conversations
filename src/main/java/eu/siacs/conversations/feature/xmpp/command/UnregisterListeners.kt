package eu.siacs.conversations.feature.xmpp.command

import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.ui.XmppActivity
import eu.siacs.conversations.xmpp.OnKeyStatusUpdated
import eu.siacs.conversations.xmpp.OnUpdateBlocklist
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class UnregisterListeners @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke() {
        if (activity is XmppConnectionService.OnConversationUpdate) {
            activity.xmppConnectionService.removeOnConversationListChangedListener(activity as XmppConnectionService.OnConversationUpdate)
        }
        if (activity is XmppConnectionService.OnAccountUpdate) {
            activity.xmppConnectionService.removeOnAccountListChangedListener(activity as XmppConnectionService.OnAccountUpdate)
        }
        if (activity is XmppConnectionService.OnCaptchaRequested) {
            activity.xmppConnectionService.removeOnCaptchaRequestedListener(activity as XmppConnectionService.OnCaptchaRequested)
        }
        if (activity is XmppConnectionService.OnRosterUpdate) {
            activity.xmppConnectionService.removeOnRosterUpdateListener(activity as XmppConnectionService.OnRosterUpdate)
        }
        if (activity is XmppConnectionService.OnMucRosterUpdate) {
            activity.xmppConnectionService.removeOnMucRosterUpdateListener(activity as XmppConnectionService.OnMucRosterUpdate)
        }
        if (activity is OnUpdateBlocklist) {
            activity.xmppConnectionService.removeOnUpdateBlocklistListener(activity as OnUpdateBlocklist)
        }
        if (activity is XmppConnectionService.OnShowErrorToast) {
            activity.xmppConnectionService.removeOnShowErrorToastListener(activity as XmppConnectionService.OnShowErrorToast)
        }
        if (activity is OnKeyStatusUpdated) {
            activity.xmppConnectionService.removeOnNewKeysAvailableListener(activity as OnKeyStatusUpdated)
        }
    }
}