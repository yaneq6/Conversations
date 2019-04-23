package eu.siacs.conversations.utils;

import android.app.Activity;
import android.content.Intent;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.features.EditAccount;
import eu.siacs.conversations.features.ManageAccount;
import eu.siacs.conversations.features.StartConversation;
import eu.siacs.conversations.features.Welcome;
import eu.siacs.conversations.features.XmppAccountManager;
import eu.siacs.conversations.services.XmppConnectionService;

public class SignupUtils {

    public static Intent getSignUpIntent(final Activity activity) {
        Intent intent = new Intent(activity, Welcome.ACTIVITY_CLASS);
        StartConversation.addInviteUri(intent, activity.getIntent());
        return intent;
    }

    public static <T extends Activity & XmppAccountManager>Intent getRedirectionIntent(final T activity) {
        final XmppConnectionService service = activity.getXmppConnectionService();
        Account pendingAccount = AccountUtils.getPendingAccount(service);
        Intent intent;
        if (pendingAccount != null) {
            intent = new Intent(activity, EditAccount.ACTIVITY_CLASS);
            intent.putExtra("jid", pendingAccount.getJid().asBareJid().toString());
        } else {
            if (service.getAccounts().size() == 0) {
                if (Config.X509_VERIFICATION) {
                    intent = new Intent(activity, ManageAccount.ACTIVITY_CLASS);
                } else if (Config.MAGIC_CREATE_DOMAIN != null) {
                    intent = getSignUpIntent(activity);
                } else {
                    intent = new Intent(activity, EditAccount.ACTIVITY_CLASS);
                }
            } else {
                intent = new Intent(activity, StartConversation.ACTIVITY_CLASS);
            }
        }
        intent.putExtra("init", true);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return intent;
    }
}