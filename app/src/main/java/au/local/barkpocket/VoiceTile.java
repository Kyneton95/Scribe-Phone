package au.local.barkpocket;

import android.app.PendingIntent;
import android.content.Intent;
import android.service.quicksettings.TileService;

public class VoiceTile extends TileService {
    @Override public void onClick() {
        super.onClick();
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        // minSdk 34: the Intent overload is deprecated and throws on target 34+.
        startActivityAndCollapse(PendingIntent.getActivity(this, 42, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }
}
