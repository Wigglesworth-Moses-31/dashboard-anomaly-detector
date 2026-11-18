package lab.anomaly.config.settings;

/** A change the settings cannot take as asked (answered 409 with this text, which never holds a key). */
public class SettingsConflict extends RuntimeException {
    public SettingsConflict(String message) {
        super(message);
    }
}
