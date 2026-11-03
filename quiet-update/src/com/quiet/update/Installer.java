package com.quiet.update;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Streams an APK into a PackageInstaller session; the system shows its own confirm dialog. */
public class Installer extends BroadcastReceiver {

    static void install(Context c, File apk) throws IOException {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setSize(apk.length());
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (OutputStream out = s.openWrite("base.apk", 0, apk.length());
                 InputStream in = new FileInputStream(apk)) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent done = new Intent(c, Installer.class).putExtra("apk", apk.getAbsolutePath());
            PendingIntent p = PendingIntent.getBroadcast(c, id, done, PendingIntent.FLAG_UPDATE_CURRENT);
            s.commit(p.getIntentSender());
        }
    }

    @Override
    public void onReceive(Context c, Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(confirm);
            }
            return;
        }
        String apk = i.getStringExtra("apk");
        if (apk != null) new File(apk).delete();
        if (status != PackageInstaller.STATUS_SUCCESS && status != PackageInstaller.STATUS_FAILURE_ABORTED) {
            String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(c, "Install failed" + (msg == null ? "" : ": " + msg), Toast.LENGTH_LONG).show();
        }
    }
}
