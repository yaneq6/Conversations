package eu.siacs.conversations.features;

public abstract class ChooseAccountForProfilePicture {
    public static final String ACTIVITY_CLASS_NAME = "eu.siacs.conversations.ui.ChooseAccountForProfilePicture";
    public static Class<?> ACTIVITY_CLASS;
    static {
        try {
            ACTIVITY_CLASS = Class.forName(ACTIVITY_CLASS_NAME);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
    }
}
