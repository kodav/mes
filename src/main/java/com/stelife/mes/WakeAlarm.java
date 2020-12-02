package com.stelife.mes;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

import java.util.Calendar;

import static android.content.Context.POWER_SERVICE;
import static com.stelife.mes.Common.LOG_TAG;
import static com.stelife.mes.Common.saveToLog;
import static com.stelife.mes.LService.ALARM_CODE;

public class WakeAlarm extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        PowerManager powerManager = (PowerManager) context.getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            PowerManager.WakeLock wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK |PowerManager.ACQUIRE_CAUSES_WAKEUP |PowerManager.ON_AFTER_RELEASE, context.getPackageName() + "::mesWakelockTag");
            wakeLock.acquire();
            saveToLog("Wake up!!!", context);
            Log.d(LOG_TAG, "Wake up!!!");

            AlarmManager alarm = (AlarmManager)context.getSystemService(Context.ALARM_SERVICE);
            Intent aint = new Intent(context, WakeAlarm.class);
            final PendingIntent pendingIntent = PendingIntent.getBroadcast(context, ALARM_CODE, aint, PendingIntent.FLAG_UPDATE_CURRENT);
            Calendar time = Calendar.getInstance();
            time.setTimeInMillis(System.currentTimeMillis());
            time.add(Calendar.SECOND, 3);
            alarm.setExact(AlarmManager.RTC_WAKEUP, time.getTimeInMillis(), pendingIntent);
            Log.d(LOG_TAG, "Set alarm");
            saveToLog("Set alarm", context);
            wakeLock.release();
        }
    }
}
