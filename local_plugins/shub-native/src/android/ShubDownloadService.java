package com.shub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.PluginResult;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Indirmeyi on plan servisinde yapar: uygulama arka plana alinsa da indirme surer.
 * Dosya uygulamanin ozel klasorune (files/apks) iner.
 */
public class ShubDownloadService extends Service {
    static volatile CallbackContext cb;          // JS tarafina ilerleme gondermek icin
    private static final String CH = "shub_dl";
    private static final int NID = 7001, NID_DONE = 7002;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        final String url = intent.getStringExtra("url");
        final String name = intent.getStringExtra("name");
        createChannel();
        Notification n = build("S-Hub Store", "İndiriliyor…", 0, false).build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NID, n);
        }
        new Thread(new Runnable() {
            @Override public void run() { work(url, name); }
        }).start();
        return START_NOT_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CH, "İndirmeler", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
    }

    private PendingIntent openApp() {
        Intent li = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (li == null) li = new Intent();
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, 0, li, fl);
    }

    private NotificationCompat.Builder build(String title, String text, int p, boolean showProgress) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH)
                .setSmallIcon(getApplicationInfo().icon)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openApp())
                .setOnlyAlertOnce(true)
                .setOngoing(true);
        if (showProgress) b.setProgress(100, p, false);
        return b;
    }

    private void progress(int p) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationCompat.Builder b = build("S-Hub Store", "İndiriliyor… %" + p, p, true);
        nm.notify(NID, b.build());
    }

    private void finish(boolean ok, String msg) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        stopForeground(true);
        nm.cancel(NID);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH)
                .setSmallIcon(getApplicationInfo().icon)
                .setContentTitle("S-Hub Store")
                .setContentText(ok ? "İndirme tamamlandı. Kurmak için dokun." : "İndirme başarısız oldu.")
                .setContentIntent(openApp())
                .setAutoCancel(true);
        nm.notify(NID_DONE, b.build());
        stopSelf();
    }

    private void work(String url, String name) {
        File dir = new File(getFilesDir(), "apks");
        if (!dir.exists()) dir.mkdirs();
        String fn = new File(name).getName();
        File part = new File(dir, fn + ".part");
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() / 100 != 2) { fail("http_" + c.getResponseCode()); return; }
            long total = c.getContentLengthLong();
            InputStream in = c.getInputStream();
            OutputStream out = new FileOutputStream(part);
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int last = -1, n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    int p = (int) (done * 100 / total);
                    if (p != last) {
                        last = p;
                        progress(p);
                        CallbackContext k = cb;
                        if (k != null) {
                            JSONObject o = new JSONObject();
                            o.put("p", p);
                            PluginResult r = new PluginResult(PluginResult.Status.OK, o);
                            r.setKeepCallback(true);
                            k.sendPluginResult(r);
                        }
                    }
                }
            }
            out.flush(); out.close(); in.close();
            File dst = new File(dir, fn);
            if (dst.exists()) dst.delete();
            if (!part.renameTo(dst)) { fail("rename_failed"); return; }
            CallbackContext k = cb;
            if (k != null) {
                JSONObject fin = new JSONObject();
                fin.put("done", true);
                k.success(fin);
            }
            finish(true, null);
        } catch (Exception e) {
            part.delete();
            fail(String.valueOf(e.getMessage()));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void fail(String msg) {
        CallbackContext k = cb;
        if (k != null) k.error(msg);
        finish(false, msg);
    }
}
