package eu.siacs.conversations.feature.conversation

import android.app.Activity
import android.widget.Toast

fun Activity.hidePrepareFileToast(prepareFileToast: Toast?) {
    prepareFileToast?.run { runOnUiThread { cancel() } }
}