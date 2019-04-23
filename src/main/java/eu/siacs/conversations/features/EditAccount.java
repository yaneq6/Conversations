package eu.siacs.conversations.features;

public abstract class EditAccount {
    public static final String EXTRA_OPENED_FROM_NOTIFICATION = "opened_from_notification";
    public static final String ACTIVITY_CLASS_NAME = "eu.siacs.conversations.ui.EditAccountActivity";
    public static Class<?> ACTIVITY_CLASS;
    static {
        try {
            ACTIVITY_CLASS = Class.forName(ACTIVITY_CLASS_NAME);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
    }
}
