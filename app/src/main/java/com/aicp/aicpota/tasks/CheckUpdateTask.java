/*
 * Copyright (C) 2015 Chandra Poerwanto
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.aicp.aicpota.tasks;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Message;

import com.aicp.aicpota.MainActivity;
import com.aicp.aicpota.R;
import com.aicp.aicpota.configs.AppConfig;
import com.aicp.aicpota.configs.LinkConfig;
import com.aicp.aicpota.configs.OTAConfig;
import com.aicp.aicpota.configs.OTAVersion;
import com.aicp.aicpota.dialogs.WaitDialogHandler;
import com.aicp.aicpota.utils.OTAUtils;
import com.aicp.aicpota.xml.OTADevice;
import com.aicp.aicpota.xml.OTAParser;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;

public class CheckUpdateTask extends AsyncTask<Context, Void, OTADevice> {

    public static String CHAN_ID = "AICP_OTA";
    private static CheckUpdateTask mInstance = null;
    private final Handler mHandler = new WaitDialogHandler();
    private Context mContext;
    private boolean mIsBackgroundThread;

    private CheckUpdateTask(boolean isBackgroundThread) {
        this.mIsBackgroundThread = isBackgroundThread;
    }

    public static CheckUpdateTask getInstance(boolean isBackgroundThread) {
        if (mInstance == null) {
            mInstance = new CheckUpdateTask(isBackgroundThread);
        }
        return mInstance;
    }

    @Override
    protected OTADevice doInBackground(Context... params) {
        mContext = params[0];

        if (!isConnectivityAvailable(mContext)) {
            return null;
        }

        showWaitDialog();

        OTADevice device = null;
        String deviceName = OTAUtils.getDeviceName(mContext);
        OTAUtils.logInfo("deviceName: " + deviceName);
        if (!deviceName.isEmpty()) {
            try {
                String otaUrl = OTAConfig.getInstance(mContext).getOtaUrl();
                InputStream is = OTAUtils.downloadURL(otaUrl);
                if (is != null) {
                    final String releaseType = OTAConfig.getInstance(mContext).getReleaseType();
                    device = OTAParser.getInstance().parse(is, deviceName, releaseType);
                    is.close();
                }
            } catch (IOException | XmlPullParserException e) {
                OTAUtils.logError(e);
            }
        }

        return device;
    }

    @Override
    protected void onPostExecute(OTADevice device) {
        super.onPostExecute(device);

        if (device == null) {
            showToast(R.string.check_update_failed);
        } else {
            String latestVersion = device.getLatestVersion();
            boolean updateAvailable = OTAVersion.checkServerVersion(latestVersion, mContext);
            if (updateAvailable) {
                showNotification(mContext);
            } else {
                showToast(R.string.no_update_available);
            }
            AppConfig.persistLatestVersion(latestVersion, mContext);
            LinkConfig.persistLinks(device.getLinks(), mContext);
        }

        AppConfig.persistLastCheck(mContext);

        hideWaitDialog();

        mInstance = null;
    }

    @Override
    protected void onCancelled() {
        super.onCancelled();
        mInstance = null;
    }

    private void showWaitDialog() {
        if (!mIsBackgroundThread) {
            Message msg = mHandler.obtainMessage(WaitDialogHandler.MSG_SHOW_DIALOG);
            msg.obj = mContext;
            mHandler.sendMessage(msg);
        }
    }

    private void hideWaitDialog() {
        if (!mIsBackgroundThread) {
            Message msg = mHandler.obtainMessage(WaitDialogHandler.MSG_CLOSE_DIALOG);
            mHandler.sendMessage(msg);
        }
    }

    private void showToast(int messageId) {
        if (!mIsBackgroundThread) {
            OTAUtils.toast(messageId, mContext);
        }
    }

    private static boolean isConnectivityAvailable(Context context) {
        ConnectivityManager connMgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = connMgr.getActiveNetworkInfo();
        return (netInfo != null && netInfo.isConnected());
    }

    private static void setupNotificationChannels(Context context) {
        NotificationManager notiman = context.getSystemService(NotificationManager.class);
        NotificationChannel otaChan = new NotificationChannel(CHAN_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        notiman.createNotificationChannel(otaChan);
    }

    private void showNotification(Context context) {
        setupNotificationChannels(context);

        Notification.Builder builder = new Notification.Builder(context);
        builder.setContentTitle(context.getString(R.string.notification_title));
        builder.setContentText(context.getString(R.string.notification_message));
        builder.setSmallIcon(R.drawable.ic_launcher_mono);
        builder.setLargeIcon(BitmapFactory.decodeResource(context.getResources(), R.mipmap.ic_launcher));
        builder.setChannel(CHAN_ID);

        Intent intent = new Intent(context, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_ONE_SHOT);
        builder.setContentIntent(pendingIntent);

        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification notification = builder.build();
        notification.flags |= Notification.FLAG_AUTO_CANCEL;
        notificationManager.notify(1000001, notification);
    }
}
