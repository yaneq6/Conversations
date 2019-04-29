package eu.siacs.conversations.ui

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import eu.siacs.conversations.activityNavigator

class ConversationActivity : AppCompatActivity() {

    private val navigate by lazy { activityNavigator(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        navigate.conversationsActivity()
        finish()
    }
}