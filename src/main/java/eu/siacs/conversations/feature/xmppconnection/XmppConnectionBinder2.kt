package eu.siacs.conversations.feature.xmppconnection

import android.os.Binder
import eu.siacs.conversations.feature.di.ServiceScope
import eu.siacs.conversations.services.XmppConnectionService
import javax.inject.Inject

@ServiceScope
class XmppConnectionBinder2 @Inject constructor(
    val service: XmppConnectionService
) : Binder()