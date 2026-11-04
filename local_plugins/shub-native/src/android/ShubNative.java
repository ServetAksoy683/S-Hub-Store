package com.shub;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.Settings;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.FileProvider;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.unity3d.ads.IUnityAdsInitializationListener;
import com.unity3d.ads.IUnityAdsLoadListener;
import com.unity3d.ads.IUnityAdsShowListener;
import com.unity3d.ads.UnityAds;
import com.unity3d.ads.UnityAdsShowOptions;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * S-Hub Store yerel koprusu.
 * Dosyalar uygulamanin ozel klasorune (files/apks) iner; dosya yoneticisinde gorunmez.
 */
public class ShubNative extends CordovaPlugin {
    private static final int REQ_NOTIF = 9001;
    private CallbackContext notifCb;

    private Context ctx() { return cordova.getActivity().getApplicationContext(); }

    private File apkDir() {
        File d = new File(ctx().getFilesDir(), "apks");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    // Yol gezinmesini engelle: yalnizca duz dosya adi
    private File apkFile(String name) {
        return new File(apkDir(), new File(name).getName());
    }

    private boolean notifOk() {
        if (Build.VERSION.SDK_INT >= 33) {
            return ctx().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                    && NotificationManagerCompat.from(ctx()).areNotificationsEnabled();
        }
        return NotificationManagerCompat.from(ctx()).areNotificationsEnabled();
    }

    private boolean installOk() {
        if (Build.VERSION.SDK_INT < 26) return true;
        return ctx().getPackageManager().canRequestPackageInstalls();
    }

    @Override
    public boolean execute(String action, JSONArray args, final CallbackContext cb) throws JSONException {
        switch (action) {
            case "unityShow": {
                final String gameId = args.getString(0);
                final String placement = args.getString(1);
                final boolean testMode = args.getBoolean(2);
                cordova.getActivity().runOnUiThread(new Runnable() {
                    @Override public void run() { unityRun(gameId, placement, testMode, cb); }
                });
                return true;
            }
            case "perms": {
                JSONObject o = new JSONObject();
                o.put("notif", notifOk());
                o.put("inst", installOk());
                cb.success(o);
                return true;
            }
            case "appInfo": {
                try {
                    android.content.pm.PackageInfo pi = ctx().getPackageManager().getPackageInfo(ctx().getPackageName(), 0);
                    JSONObject o = new JSONObject();
                    o.put("versionName", pi.versionName);
                    o.put("versionCode", Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode);
                    cb.success(o);
                } catch (Exception e) {
                    cb.error(String.valueOf(e.getMessage()));
                }
                return true;
            }
            case "requestNotif": {
                if (notifOk()) { cb.success(); return true; }
                if (Build.VERSION.SDK_INT >= 33
                        && ctx().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notifCb = cb;
                    cordova.requestPermission(this, REQ_NOTIF, Manifest.permission.POST_NOTIFICATIONS);
                } else {
                    // izin verilmis ama bildirimler kapatilmis -> uygulama bildirim ayarlarini ac
                    Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx().getPackageName())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx().startActivity(i);
                    cb.success();
                }
                return true;
            }
            case "openInstallSettings": {
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + ctx().getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx().startActivity(i);
                cb.success();
                return true;
            }
            case "download": {
                final String url = args.getString(0);
                final String name = args.getString(1);
                ShubDownloadService.cb = cb;
                Intent si = new Intent(ctx(), ShubDownloadService.class)
                        .putExtra("url", url).putExtra("name", name);
                if (Build.VERSION.SDK_INT >= 26) ctx().startForegroundService(si);
                else ctx().startService(si);
                return true;
            }
            case "install": {
                File f = apkFile(args.getString(0));
                if (!f.exists()) { cb.error("not_found"); return true; }
                Uri uri = FileProvider.getUriForFile(ctx(), ctx().getPackageName() + ".shubprovider", f);
                Intent i = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx().startActivity(i);
                cb.success();
                return true;
            }
            case "exportToDownloads": {
                final String name = args.getString(0);
                cordova.getThreadPool().execute(new Runnable() {
                    @Override public void run() { export(name, cb); }
                });
                return true;
            }
        }
        return false;
    }

    private void download(String url, String name, CallbackContext cb) {
        HttpURLConnection c = null;
        File part = new File(apkDir(), new File(name).getName() + ".part");
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() / 100 != 2) { cb.error("http_" + c.getResponseCode()); return; }
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
                        JSONObject o = new JSONObject();
                        o.put("p", p);
                        PluginResult r = new PluginResult(PluginResult.Status.OK, o);
                        r.setKeepCallback(true);
                        cb.sendPluginResult(r);
                    }
                }
            }
            out.flush(); out.close(); in.close();
            File dst = apkFile(name);
            if (dst.exists()) dst.delete();
            if (!part.renameTo(dst)) { cb.error("rename_failed"); return; }
            JSONObject fin = new JSONObject();
            fin.put("done", true);
            cb.success(fin);
        } catch (Exception e) {
            part.delete();
            cb.error(String.valueOf(e.getMessage()));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // Yalnizca premium kullanicilar icin: dosyanin kopyasini Downloads klasorune koyar
    private void export(String name, CallbackContext cb) {
        try {
            File src = apkFile(name);
            if (!src.exists()) { cb.error("not_found"); return; }
            if (Build.VERSION.SDK_INT < 29) { cb.error("unsupported"); return; }
            ContentResolver cr = ctx().getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, src.getName());
            v.put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive");
            v.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) { cb.error("insert_failed"); return; }
            OutputStream out = cr.openOutputStream(uri);
            InputStream in = new FileInputStream(src);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close(); in.close();
            v.clear();
            v.put(MediaStore.Downloads.IS_PENDING, 0);
            cr.update(uri, v, null, null);
            cb.success();
        } catch (Exception e) {
            cb.error(String.valueOf(e.getMessage()));
        }
    }

    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_NOTIF && notifCb != null) {
            notifCb.success();
            notifCb = null;
        }
    }

    /* ---- Unity Ads (odullu reklam) ---- */
    private void unityRun(final String gameId, final String placement, final boolean testMode, final CallbackContext cb) {
        final android.app.Activity act = cordova.getActivity();
        if (UnityAds.isInitialized()) { unityLoad(act, placement, cb); return; }
        UnityAds.initialize(act.getApplicationContext(), gameId, testMode, new IUnityAdsInitializationListener() {
            @Override public void onInitializationComplete() { unityLoad(act, placement, cb); }
            @Override public void onInitializationFailed(UnityAds.UnityAdsInitializationError error, String message) {
                cb.error("fail:init:" + message);
            }
        });
    }

    private void unityLoad(final android.app.Activity act, final String placement, final CallbackContext cb) {
        UnityAds.load(placement, new IUnityAdsLoadListener() {
            @Override public void onUnityAdsAdLoaded(String placementId) { unityShow(act, placementId, cb); }
            @Override public void onUnityAdsFailedToLoad(String placementId, UnityAds.UnityAdsLoadError error, String message) {
                cb.error("fail:load:" + message);
            }
        });
    }

    private void unityShow(final android.app.Activity act, final String placement, final CallbackContext cb) {
        UnityAds.show(act, placement, new UnityAdsShowOptions(), new IUnityAdsShowListener() {
            @Override public void onUnityAdsShowFailure(String placementId, UnityAds.UnityAdsShowError error, String message) {
                cb.error("fail:show:" + message);
            }
            @Override public void onUnityAdsShowStart(String placementId) { }
            @Override public void onUnityAdsShowClick(String placementId) { }
            @Override public void onUnityAdsShowComplete(String placementId, UnityAds.UnityAdsShowCompletionState state) {
                if (state == UnityAds.UnityAdsShowCompletionState.COMPLETED) cb.success("ok");
                else cb.error("skipped");
            }
        });
    }
}
