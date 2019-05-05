package eu.siacs.conversations.feature.xmpp

import android.content.ContextWrapper
import android.view.View
import android.widget.ImageView
import eu.siacs.conversations.ui.XmppActivity
import java.lang.ref.WeakReference


fun find(viewWeakReference: WeakReference<ImageView>): XmppActivity? {
    val view = viewWeakReference.get()
    return if (view == null) null else find(view)
}

fun find(view: View): XmppActivity? {
    var context = view.context
    while (context is ContextWrapper) {
        if (context is XmppActivity) {
            return context
        }
        context = context.baseContext
    }
    return null
}