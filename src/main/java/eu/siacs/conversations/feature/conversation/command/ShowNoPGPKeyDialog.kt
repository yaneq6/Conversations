package eu.siacs.conversations.feature.conversation.command

import android.content.DialogInterface
import android.support.v7.app.AlertDialog
import eu.siacs.conversations.R
import eu.siacs.conversations.ui.ConversationFragment
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class ShowNoPGPKeyDialog @Inject constructor(
    private val fragment: ConversationFragment
) : (Boolean, DialogInterface.OnClickListener) -> Unit {
    override fun invoke(plural: Boolean, listener: DialogInterface.OnClickListener) = fragment.run {
        val builder = AlertDialog.Builder(getActivity())
        builder.setIconAttribute(android.R.attr.alertDialogIcon)
        if (plural) {
            builder.setTitle(getString(R.string.no_pgp_keys))
            builder.setMessage(getText(R.string.contacts_have_no_pgp_keys))
        } else {
            builder.setTitle(getString(R.string.no_pgp_key))
            builder.setMessage(getText(R.string.contact_has_no_pgp_key))
        }
        builder.setNegativeButton(getString(R.string.cancel), null)
        builder.setPositiveButton(getString(R.string.send_unencrypted), listener)
        builder.create().show()
    }
}