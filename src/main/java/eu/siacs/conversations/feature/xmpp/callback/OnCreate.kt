package eu.siacs.conversations.feature.xmpp.callback

import android.content.pm.PackageManager
import android.content.res.Resources
import android.media.AudioManager
import android.os.Bundle
import eu.siacs.conversations.feature.xmpp.query.UsingEnterKey
import eu.siacs.conversations.ui.XmppActivity
import eu.siacs.conversations.ui.service.EmojiService
import eu.siacs.conversations.utils.ExceptionHelper
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@ActivityScope
class OnCreate @Inject constructor(
    private val activity: XmppActivity,
    private val resources: Resources,
    private val packageManager: PackageManager,
    private val usingEnterKey: UsingEnterKey
) {

    operator fun invoke(savedInstanceState: Bundle?) {
        activity.volumeControlStream = AudioManager.STREAM_NOTIFICATION
        activity.metrics = resources.displayMetrics
        ExceptionHelper.init(activity.applicationContext)
        EmojiService(activity).init()
        activity.isCameraFeatureAvailable =
            packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)
        activity.mTheme = activity.findTheme()
        activity.setTheme(activity.mTheme)

        activity.mUsingEnterKey = usingEnterKey()
    }
}