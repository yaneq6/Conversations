package eu.siacs.conversations.feature.xmpp.command

import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.ui.XmppActivity
import eu.siacs.conversations.xmpp.OnKeyStatusUpdated
import eu.siacs.conversations.xmpp.OnUpdateBlocklist
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class RegisterListeners @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke() {
        if (activity is XmppConnectionService.OnConversationUpdate) {
            activity.xmppConnectionService.setOnConversationListChangedListener(activity as XmppConnectionService.OnConversationUpdate)
        }
        if (activity is XmppConnectionService.OnAccountUpdate) {
            activity.xmppConnectionService.setOnAccountListChangedListener(activity as XmppConnectionService.OnAccountUpdate)
        }
        if (activity is XmppConnectionService.OnCaptchaRequested) {
            activity.xmppConnectionService.setOnCaptchaRequestedListener(activity as XmppConnectionService.OnCaptchaRequested)
        }
        if (activity is XmppConnectionService.OnRosterUpdate) {
            activity.xmppConnectionService.setOnRosterUpdateListener(activity as XmppConnectionService.OnRosterUpdate)
        }
        if (activity is XmppConnectionService.OnMucRosterUpdate) {
            activity.xmppConnectionService.setOnMucRosterUpdateListener(activity as XmppConnectionService.OnMucRosterUpdate)
        }
        if (activity is OnUpdateBlocklist) {
            activity.xmppConnectionService.setOnUpdateBlocklistListener(activity as OnUpdateBlocklist)
        }
        if (activity is XmppConnectionService.OnShowErrorToast) {
            activity.xmppConnectionService.setOnShowErrorToastListener(activity as XmppConnectionService.OnShowErrorToast)
        }
        if (activity is OnKeyStatusUpdated) {
            activity.xmppConnectionService.setOnKeyStatusUpdatedListener(activity as OnKeyStatusUpdated)
        }
    }
}