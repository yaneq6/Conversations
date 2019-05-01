package eu.siacs.conversations.ui

import android.content.Intent
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
//import eu.siacs.conversations.activityNavigator

class ConversationActivity : AppCompatActivity() {

//    private val navigate by lazy { activityNavigator(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
//        navigate.conversationsActivity()
        startActivity(Intent(this, ConversationsActivity::class.java))
        finish()
    }
}
