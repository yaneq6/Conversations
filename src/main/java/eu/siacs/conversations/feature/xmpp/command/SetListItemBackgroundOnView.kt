package eu.siacs.conversations.feature.xmpp.command

import android.annotation.TargetApi
import android.content.res.Resources
import android.os.Build
import android.view.View
import eu.siacs.conversations.R
import io.aakit.scope.ActivityScope
import javax.inject.Inject

@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
@ActivityScope
class SetListItemBackgroundOnView @Inject constructor(
    private val resources: Resources
) {

    operator fun invoke(view: View) {
        val sdk = Build.VERSION.SDK_INT
        if (sdk < Build.VERSION_CODES.JELLY_BEAN) {
            view.setBackgroundDrawable(resources.getDrawable(R.drawable.greybackground))
        } else {
            view.background = resources.getDrawable(R.drawable.greybackground)
        }
    }
}