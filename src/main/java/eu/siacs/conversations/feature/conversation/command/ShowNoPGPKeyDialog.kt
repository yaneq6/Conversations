package eu.siacs.conversations.feature.conversation.command

import android.app.Activity
import android.content.DialogInterface
import android.support.v7.app.AlertDialog
import eu.siacs.conversations.R
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class ShowNoPGPKeyDialog @Inject constructor(
    private val activity: Activity
) : (Boolean, DialogInterface.OnClickListener) -> Unit {

    override fun invoke(plural: Boolean, listener: DialogInterface.OnClickListener) = AlertDialog
        .Builder(activity).run {
            setIconAttribute(android.R.attr.alertDialogIcon)
            activity.run {
                if (plural) {
                    title = getString(R.string.no_pgp_keys)
                    setMessage(getText(R.string.contacts_have_no_pgp_keys))
                } else {
                    title = getString(R.string.no_pgp_key)
                    setMessage(getText(R.string.contact_has_no_pgp_key))
                }
                setNegativeButton(getString(R.string.cancel), null)
                setPositiveButton(getString(R.string.send_unencrypted), listener)
            }

        }.create().show()
}