package eu.siacs.conversations.feature.conversations

import eu.siacs.conversations.R
import eu.siacs.conversations.ui.ConversationsActivity
import eu.siacs.conversations.ui.ConversationsOverviewFragment
import eu.siacs.conversations.utils.ExceptionHelper
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class ShowDialogsIfMainIsOverviewCommand @Inject constructor(
    private val activity: ConversationsActivity,
    private val openBatteryOptimizationDialogIfNeeded: OpenBatteryOptimizationDialogIfNeededCommand
) : () -> Unit {
    override fun invoke(): Unit = activity.run {
        xmppConnectionService
            ?.let { fragmentManager.findFragmentById(R.id.main_fragment) }
            ?.takeIf { it is ConversationsOverviewFragment }
            ?.takeUnless { ExceptionHelper.checkForCrash(this) }
            ?.let { openBatteryOptimizationDialogIfNeeded() }
    }
}