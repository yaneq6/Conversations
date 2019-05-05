package eu.siacs.conversations.feature.xmpp.command

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.databinding.DataBindingUtil
import android.support.annotation.StringRes
import android.support.v7.app.AlertDialog
import android.text.InputType
import eu.siacs.conversations.R
import eu.siacs.conversations.databinding.DialogQuickeditBinding
import eu.siacs.conversations.ui.XmppActivity
import eu.siacs.conversations.ui.util.SoftKeyboardUtils
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class QuickEdit @Inject constructor(
    private val activity: XmppActivity
) {
    operator fun invoke(
        previousValue: String,
        @StringRes hint: Int,
        callback: XmppActivity.OnValueEdited
    ) {
        invoke(previousValue, callback, hint, password = false, permitEmpty = false)
    }

    operator fun invoke(
        previousValue: String,
        @StringRes hint: Int,
        onValueEdited: (String) -> String?,
        permitEmpty: Boolean
    ) {
        invoke(previousValue, onValueEdited, hint, false, permitEmpty)
    }

    @SuppressLint("InflateParams")
    operator fun invoke(
        previousValue: String?,
        onValueEdited: (String) -> String?,
        @StringRes hint: Int,
        password: Boolean,
        permitEmpty: Boolean
    ) {
        val builder = AlertDialog.Builder(activity)
        val binding =
            DataBindingUtil.inflate<DialogQuickeditBinding>(
                activity.layoutInflater,
                R.layout.dialog_quickedit,
                null,
                false
            )
        if (password) {
            binding.inputEditText.inputType =
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        builder.setPositiveButton(R.string.accept, null)
        if (hint != 0) {
            binding.inputLayout.hint = activity.getString(hint)
        }
        binding.inputEditText.requestFocus()
        if (previousValue != null) {
            binding.inputEditText.text!!.append(previousValue)
        }
        builder.setView(binding.root)
        builder.setNegativeButton(R.string.cancel, null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            SoftKeyboardUtils.showKeyboard(
                binding.inputEditText
            )
        }
        dialog.show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val value = binding.inputEditText.text!!.toString()
            if (value != previousValue && (value.trim { it <= ' ' }.isNotEmpty() || permitEmpty)) {
                val error = onValueEdited(value)
                if (error != null) {
                    binding.inputLayout.error = error
                    return@setOnClickListener
                }
            }
            SoftKeyboardUtils.hideSoftKeyboard(binding.inputEditText)
            dialog.dismiss()
        }
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener {
            SoftKeyboardUtils.hideSoftKeyboard(binding.inputEditText)
            dialog.dismiss()
        }
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener {
            SoftKeyboardUtils.hideSoftKeyboard(binding.inputEditText)
        }
    }
}