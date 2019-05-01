package eu.siacs.conversations.ui

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import eu.siacs.conversations.activityNavigator

class ConversationActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityNavigator().conversationsActivity()
        finish()
    }
}
