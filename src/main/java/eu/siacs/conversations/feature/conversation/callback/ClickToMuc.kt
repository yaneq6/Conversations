package eu.siacs.conversations.feature.conversation.callback

import android.content.Intent
import android.view.View
import eu.siacs.conversations.ui.ConferenceDetailsActivity
import eu.siacs.conversations.ui.ConversationFragment
import eu.siacs.conversations.ui.ConversationsActivity
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class ClickToMuc @Inject constructor(
    private val fragment: ConversationFragment,
    private val activity: ConversationsActivity
) : View.OnClickListener {
    override fun onClick(v: View?) {
        val intent = Intent(
            activity,
            ConferenceDetailsActivity::class.java
        )
        intent.action = ConferenceDetailsActivity.ACTION_VIEW_MUC
        intent.putExtra("uuid", fragment.conversation!!.uuid)
        fragment.startActivity(intent)
    }
}