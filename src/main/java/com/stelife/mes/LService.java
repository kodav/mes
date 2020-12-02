package com.stelife.mes;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.maps.model.LatLng;
import com.google.maps.android.SphericalUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.util.Calendar;

import static com.stelife.mes.Common.JSON_FILE;
import static com.stelife.mes.Common.LOG_TAG;
import static com.stelife.mes.Common.saveToLog;
import static java.lang.Math.random;
import static java.lang.Math.round;

public class LService extends Service {
    static final int DEFAULT_NOTIFICATION_ID = 7778;
    static final int ALARM_CODE = 7779;
    static final String MESSAGE_MES = "com.stelife.mes.message";
    static final String NOTIFICATION_CHANNEL_ID = "com.stelife.mes.notif";
    static final String channelName = "MES Service";

    private NotificationManager notificationManager;
    public static final String LOCATION_PROVIDER = LocationManager.GPS_PROVIDER;
    private final IBinder mBinder = new LocalService();
    private boolean isRun = false;
    private JSONObject current_pos;
    public static Thread task;
    private static PendingIntent contentIntent;

    public LatLng getCurrentPos() throws JSONException {
        return new LatLng(current_pos.getDouble("lat"), current_pos.getDouble("lon"));
    }

    class LocalService extends Binder {
        LService getService() {
            return LService.this;
        }
    }

    boolean isStart() {
        return isRun;
    }

    private void setAlarm(long d) {
        AlarmManager alarm = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(getApplicationContext(), WakeAlarm.class);
        final PendingIntent pendingIntent = PendingIntent.getBroadcast(this, ALARM_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        Calendar time = Calendar.getInstance();
        time.setTimeInMillis(System.currentTimeMillis());
        time.add(Calendar.SECOND, 3);
        alarm.setExact(AlarmManager.RTC_WAKEUP, time.getTimeInMillis(), pendingIntent);
        Log.d(LOG_TAG, "Set alarm "+d);
        saveToLog("Set alarm "+d, this);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setAction(Intent.ACTION_MAIN);
        notificationIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        contentIntent = PendingIntent.getActivity(getApplicationContext(), 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationChannel chan = new NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_HIGH);
        chan.setLightColor(Color.BLUE);
        chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        notificationManager.createNotificationChannel(chan);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID);
        builder.setContentIntent(contentIntent)
                .setOngoing(true)   //invulnerable
                .setTicker("MES")
                .setContentTitle("MES")
                .setContentText("Создали")
                //.setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setSmallIcon(R.mipmap.ic_launcher_foreground);
        //.setWhen(System.currentTimeMillis());

        Notification notification;
        notification = builder.build();

        startForeground(DEFAULT_NOTIFICATION_ID, notification);

        Log.d(LOG_TAG, "onCreate");
        saveToLog("onCreate", this);
    }

    public void Stop() {
        Log.d(LOG_TAG, "Stop");
        saveToLog("Stop", this);
        isRun = false;
    }

    public void sendNotification(String Ticker, String Title, String Text) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID);
        builder.setContentIntent(contentIntent)
                .setOngoing(true)   //invulnerable
                .setTicker(Ticker)
                .setContentTitle(Title)
                .setContentText(Text)
                //.setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setSmallIcon(R.mipmap.ic_launcher_foreground);
                //.setWhen(System.currentTimeMillis());
        notificationManager.notify(DEFAULT_NOTIFICATION_ID, builder.build());
    }

    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(LOG_TAG, "onStartCommand");
        saveToLog("onStartCommand", this);
        //someTask();
        new PlayGPS(this).execute();
        //return super.onStartCommand(intent, flags, startId);
        return Service.START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.i(LOG_TAG, "Service: onTaskRemoved");
        saveToLog("Service: onTaskRemoved", this);
    }

    @Override
    public void onDestroy(){
        super.onDestroy();
        Log.d(LOG_TAG, "onDestroy");
        saveToLog("onDestroy", this);
        try {
            LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            assert locationManager != null;
            locationManager.removeTestProvider(LOCATION_PROVIDER);
        }
        catch (Exception ignored) {}
        //Removing any notifications
        notificationManager.cancel(DEFAULT_NOTIFICATION_ID);
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(LOG_TAG, "onBind");
        saveToLog("onBind", this);
        return mBinder;
    }

    void sendMessage(double lat, double lon, double speed, double distance) {
        Intent intent = new Intent(MESSAGE_MES);
        intent.putExtra("name", "pos");
        intent.putExtra("longitude", lon);
        intent.putExtra("latitude", lat);
        intent.putExtra("speed", speed);
        intent.putExtra("distance", distance);
        sendBroadcast(intent);
    }

    static class PlayGPS extends AsyncTask<String, Void, Void> {
        private final WeakReference<LService> serv;

        PlayGPS(LService s) {
            serv = new WeakReference<>(s);
        }

        @Override
        protected Void doInBackground(String... strings) {
            LService s = serv.get();
            if (s == null) return null;

            s.isRun = true;
            s.setAlarm(0);
            LocationManager locationManager = (LocationManager) s.getSystemService(Context.LOCATION_SERVICE);
            if (locationManager == null) return null;
            try {
                if (!locationManager.isProviderEnabled(LOCATION_PROVIDER)) {
                    locationManager.addTestProvider(LOCATION_PROVIDER, false, true,
                            false, false, true, true, false, 0, 5);
                    locationManager.setTestProviderEnabled(LOCATION_PROVIDER, true);
                    locationManager.setTestProviderStatus(LOCATION_PROVIDER, android.location.LocationProvider.AVAILABLE, null, System.currentTimeMillis());
                }
            } catch (Exception e) {
                Log.d(LOG_TAG, "ERROR : " + e.getMessage());
                saveToLog("ERROR : " + e.getMessage(), s);
                Intent intent = new Intent(MESSAGE_MES);
                intent.putExtra("name", "gps_mock");
                intent.putExtra("message", "Не включен режим эмуляции координат.");
                s.sendBroadcast(intent);
                s.stopSelf();
                s.isRun = false;
                return null;
            }

            JSONArray jsonArray = new JSONArray();
            try {
                InputStream inputStream = s.openFileInput(JSON_FILE);
                if (inputStream != null) {
                    InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
                    BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
                    String receiveString = "";
                    StringBuilder stringBuilder = new StringBuilder();
                    while ((receiveString = bufferedReader.readLine()) != null) {
                        stringBuilder.append(receiveString);
                    }
                    inputStream.close();
                    JSONObject root = new JSONObject(stringBuilder.toString());
                    jsonArray = root.getJSONArray("coords");
                }
            } catch (Exception ignored) {
            }

            if (jsonArray.length() == 0){
                s.stopSelf();
                s.isRun = false;
                Intent intent = new Intent(MESSAGE_MES);
                intent.putExtra("name", "stop");
                s.sendBroadcast(intent);
            }
            s.sendNotification("MES", "MES", "Запустили");
            Log.d(LOG_TAG, "Started");
            saveToLog("Started", s);
            // convert to a simple array so we can pass it to the AsyncTask
            JSONObject point = null;
            double latitude = 0;
            double longitude = 0;
            double altitude = 0;
            int delay = 0;
            double old_lat = 0, old_lon = 0, distance = 0, speed = 0;
            Location loc = new Location(LOCATION_PROVIDER);
            synchronized (this) {
                try {
                    point = (JSONObject) jsonArray.get(0);
                    s.current_pos = point;

                    // ПЕРВАЯ ТОЧКА
                    latitude = s.current_pos.getDouble("lat");
                    longitude = s.current_pos.getDouble("lon");
                    altitude = point.getDouble("alt") + random();  // Высоту берем из точки остановки

                    loc.setLatitude(latitude + random());
                    loc.setLongitude(longitude + random());
                    loc.setAltitude(altitude);
                    loc.setAccuracy(5.0f);
                    loc.setSpeed(0.0f);
                    loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                    loc.setTime(System.currentTimeMillis());

                    // show debug message in log
                    Log.d(LOG_TAG, loc.toString());
                    saveToLog(loc.toString(), s);
                    try {
                        locationManager.setTestProviderLocation(LOCATION_PROVIDER, loc);
                    } catch (Exception e) {
                        Log.d(LOG_TAG, "ERROR : " + e.getMessage());
                        saveToLog("ERROR : " + e.getMessage(), s);
                        s.sendNotification("MES", "Ошибка", e.getMessage());
                    }

                    s.sendMessage(point.getDouble("lat"), point.getDouble("lon"), 0, 0);
                    s.sendNotification("MES", "Стоянка", "Точка остановки 1. Стояка " + point.getInt("time") + " мин.");
                    Log.d(LOG_TAG, "Точка остановки 1. Стояка " + point.getInt("time") + " мин.");
                    saveToLog("Точка остановки 1. Стояка " + point.getInt("time") + " мин.", s);

                    for (int t = 0; t < point.getInt("time") * 60; t++) {
                        if (!s.isRun) break;
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ignored) {
                        }
                        if (t % 5 == 0) {
                            // Пока стоим - показываем поступление координат вокруг точки остановки
                            latitude = point.getDouble("lat");
                            longitude = point.getDouble("lon");
                            altitude = point.getDouble("alt");  // Высоту берем из точки остановки

                            //loc = new Location(LOCATION_PROVIDER);
                            loc.setLatitude(latitude);
                            loc.setLongitude(longitude);
                            loc.setAltitude(altitude);
                            loc.setAccuracy(5.0f);
                            loc.setSpeed(0.0f);
                            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                            loc.setTime(System.currentTimeMillis());

                            // show debug message in log
                            Log.d(LOG_TAG, loc.toString());
                            saveToLog(loc.toString(), s);
                            try {
                                locationManager.setTestProviderLocation(LOCATION_PROVIDER, loc);
                            } catch (Exception e) {
                                Log.d(LOG_TAG, "ERROR : " + e.getMessage());
                                saveToLog("ERROR : " + e.getMessage(), s);
                                s.sendNotification("MES", "Ошибка", e.getMessage());
                            }
                            s.sendMessage(latitude, longitude, 0, 0);
                        }
                    }
                } catch (JSONException e) {
                    Log.d(LOG_TAG, "ERROR : " + e.getMessage());
                    saveToLog("ERROR : " + e.getMessage(), s);
                    s.sendNotification("MES", "Ошибка", e.getMessage());
                }

                // ДВИЖЕНИЕ
                for (int i = 1; i < jsonArray.length(); i++) {
                    try {
                        point = (JSONObject) jsonArray.get(i);
                        s.current_pos = point;
                    } catch (JSONException e) {
                        Log.d(LOG_TAG, "ERROR : " + e.getMessage());
                        saveToLog("ERROR : " + e.getMessage(), s);
                        s.sendNotification("MES", "Ошибка", e.getMessage());
                    }
                    if (point == null) {
                        Log.d(LOG_TAG, "ERROR : POINT NULL");
                        saveToLog("ERROR : POINT NULL", s);
                        s.sendNotification("MES", "Ошибка", "POINT NULL");
                        break;
                    }

                    try {
                        if (s.isRun && point.has("path")) {
                            JSONArray path_arr = point.getJSONArray("path");
                            for (int j = 0; j < path_arr.length(); j++) {
                                if (!s.isRun) break;
                                s.current_pos = (JSONObject) path_arr.get(j);

                                if (old_lat != 0 && old_lon != 0) {
                                    distance = SphericalUtil.computeDistanceBetween(new LatLng(s.current_pos.getDouble("lat"), s.current_pos.getDouble("lon")),
                                            new LatLng(old_lat, old_lon));
                                    if (distance > 200) {
                                        speed = 60f + (random() * 20f - 10f);
                                    } else if (distance > 50) {
                                        speed = 40f + (random() * 20f - 10f);
                                    } else {
                                        speed = 20f + (random() * 20f - 10f);
                                    }
                                    delay = (int) round(distance / (speed * 1000f / 3600f));
                                }
                                try {
                                    double heading = SphericalUtil.computeHeading(new LatLng(s.current_pos.getDouble("lat"), s.current_pos.getDouble("lon")),
                                            new LatLng(old_lat, old_lon));
                                    LatLng start_pos = new LatLng(old_lat, old_lon);
                                    float sp = 0;
                                    for (int t = 0; t < delay; t++) {
                                        if (!s.isRun) break;
                                        LatLng temp_pos = SphericalUtil.computeOffset(start_pos, distance / delay, heading + 180);
                                        altitude = point.getDouble("alt");  // Высоту берем из точки остановки
                                        //loc = new Location(LOCATION_PROVIDER);
                                        loc.setLatitude(temp_pos.latitude);
                                        loc.setLongitude(temp_pos.longitude);
                                        loc.setAltitude(altitude);
                                        loc.setAccuracy(5.0f);
                                        sp = (float) (speed + Math.random() * 6 - 3);
                                        loc.setSpeed((float) sp * 1000f / 3600f);
                                        loc.setBearing((float) (heading + 180));
                                        loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                                        loc.setTime(System.currentTimeMillis());

                                        // show debug message in log
                                        Log.d(LOG_TAG, loc.toString());
                                        saveToLog(loc.toString(), s);
                                        try {
                                            locationManager.setTestProviderLocation(LOCATION_PROVIDER, loc);
                                        } catch (Exception e) {
                                            Log.d(LOG_TAG, "ERROR : " + e.getMessage());
                                            saveToLog("ERROR : " + e.getMessage(), s);
                                            s.sendNotification("MES", "Ошибка", e.getMessage());
                                        }
                                        s.sendMessage(temp_pos.latitude, temp_pos.longitude, sp, 0);
                                        s.sendNotification("MES", "Движение к точке " + (i + 1), (int) sp + " км/ч.");
                                        start_pos = temp_pos;
                                        long d = (long) (1000 + (Math.random() * 200 - 100));
                                        Thread.sleep(d);
                                    }
                                } catch (InterruptedException ignored) {
                                }

                                //                            latitude = current_pos.getDouble("lat");
                                //                            longitude = current_pos.getDouble("lon");
                                //                            altitude = point.getDouble("alt");  // Высоту берем из точки остановки
                                //                            // translate to actual GPS location
                                //                            //loc = new Location(LOCATION_PROVIDER);
                                //                            loc.setLatitude(latitude);
                                //                            loc.setLongitude(longitude);
                                //                            loc.setAltitude(altitude);
                                //                            loc.setAccuracy(5.0f);
                                //                            loc.setSpeed((float) speed * 1000f / 3600f);
                                //                            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                                //                            loc.setTime(System.currentTimeMillis());

                                if (s.isRun) {
                                    // show debug message in log
                                    //                                Log.d(LOG_TAG, loc.toString());
                                    //                                saveToLog(loc.toString(), this);
                                    //                                try {
                                    //                                    locationManager.setTestProviderLocation(LOCATION_PROVIDER, loc);
                                    //                                } catch (Exception e) {
                                    //                                    Log.d(LOG_TAG, "ERROR : " + e.getMessage());
                                    //                                    saveToLog("ERROR : " + e.getMessage(), this);
                                    //                                    sendNotification("MES", "Ошибка",  e.getMessage());
                                    //                                }
                                    //                                sendMessage(current_pos.getDouble("lat"), current_pos.getDouble("lon"), speed, distance);
                                    //                                sendNotification("MES", "Движение к точке "+(i+1), (int) speed + " км/ч.");
                                    //                                Log.d(LOG_TAG, "Движение " + (int) distance + " м. " + (int) speed + " км/ч.");
                                    //                                saveToLog("Движение " + (int) distance + " м. " + (int) speed + " км/ч.", this);

                                    old_lat = s.current_pos.getDouble("lat");
                                    old_lon = s.current_pos.getDouble("lon");
                                }
                            }
                        }

                        // ТОЧКА ОСТАНОВКИ
                        if (s.isRun) {
                            try {
                                s.sendMessage(point.getDouble("lat"), point.getDouble("lon"), 0, 0);
                                s.sendNotification("MES", "Стоянка", "Точка остановки " + (i + 1) + ". Стояка " + point.getInt("time") + " мин.");
                                Log.d(LOG_TAG, "Точка остановки " + (i + 1) + ". Стояка " + point.getInt("time") + " мин.");
                                saveToLog("Точка остановки " + (i + 1) + ". Стояка " + point.getInt("time") + " мин.", s);

                                for (int t = 0; t < point.getInt("time") * 60; t++) {
                                    if (!s.isRun) break;
                                    try {
                                        Thread.sleep(1000);
                                    } catch (InterruptedException ignored) {
                                    }
                                    if (t % 5 == 0) {
                                        // Пока стоим - показываем поступление координат вокруг точки остановки
                                        latitude = point.getDouble("lat");
                                        longitude = point.getDouble("lon");
                                        altitude = point.getDouble("alt");  // Высоту берем из точки остановки

                                        //loc = new Location(LOCATION_PROVIDER);
                                        loc.setLatitude(latitude);
                                        loc.setLongitude(longitude);
                                        loc.setAltitude(altitude);
                                        loc.setAccuracy(5.0f);
                                        loc.setSpeed(0.0f);
                                        loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                                        loc.setTime(System.currentTimeMillis());

                                        // show debug message in log
                                        Log.d(LOG_TAG, loc.toString());
                                        saveToLog(loc.toString(), s);
                                        try {
                                            locationManager.setTestProviderLocation(LOCATION_PROVIDER, loc);
                                        } catch (Exception e) {
                                            Log.d(LOG_TAG, "ERROR : " + e.getMessage());
                                            saveToLog("ERROR : " + e.getMessage(), s);
                                            s.sendNotification("MES", "Ошибка", e.getMessage());
                                        }
                                        s.sendMessage(latitude, longitude, 0, 0);
                                    }
                                }
                            } catch (JSONException e) {
                                Log.d(LOG_TAG, "ERROR : " + e.getMessage());
                                saveToLog("ERROR : " + e.getMessage(), s);
                                s.sendNotification("MES", "Ошибка", e.getMessage());
                            }
                        }
                    } catch (JSONException e) {
                        Log.d(LOG_TAG, "ERROR : " + e.getMessage());
                        saveToLog("ERROR : " + e.getMessage(), s);
                        s.sendNotification("MES", "Ошибка", e.getMessage());
                    }
                }
            }
            s.stopSelf();
            s.sendNotification("MES", "MES", "Остановлен");
            saveToLog("Остановлен", s);
            Log.d(LOG_TAG, "Остановлен");
            s.isRun = false;
            Intent intent = new Intent(MESSAGE_MES);
            intent.putExtra("name", "stop");
            s.sendBroadcast(intent);
            return null;
        }
    }

    void someTask() {
        isRun = true;
        task = new Thread(() -> {
            LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (locationManager == null) return;
            try {
                if (!locationManager.isProviderEnabled(LOCATION_PROVIDER)) {
                    locationManager.addTestProvider(LOCATION_PROVIDER, false, true,
                            false, false, true, true, false, 0, 5);
                    locationManager.setTestProviderEnabled(LOCATION_PROVIDER, true);
                    locationManager.setTestProviderStatus(LOCATION_PROVIDER, android.location.LocationProvider.AVAILABLE, null, System.currentTimeMillis());
                }
            } catch (Exception e) {
                Log.d(LOG_TAG, "ERROR : " + e.getMessage());
                saveToLog("ERROR : " + e.getMessage(), this);
                Intent intent = new Intent(MESSAGE_MES);
                intent.putExtra("name", "gps_mock");
                intent.putExtra("message", "Не включен режим эмуляции координат.");
                sendBroadcast(intent);
                stopSelf();
                isRun = false;
                return;
            }

            JSONArray jsonArray = new JSONArray();
            try {
                InputStream inputStream = openFileInput(JSON_FILE);
                if (inputStream != null) {
                    InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
                    BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
                    String receiveString = "";
                    StringBuilder stringBuilder = new StringBuilder();
                    while ((receiveString = bufferedReader.readLine()) != null) {
                        stringBuilder.append(receiveString);
                    }
                    inputStream.close();
                    JSONObject root = new JSONObject(stringBuilder.toString());
                    jsonArray = root.getJSONArray("coords");
                }
            } catch (Exception ignored) {
            }

            if (jsonArray.length() == 0){
                stopSelf();
                isRun = false;
                Intent intent = new Intent(MESSAGE_MES);
                intent.putExtra("name", "stop");
                sendBroadcast(intent);
            }
            sendNotification("MES", "MES", "Запустили");
            Log.d(LOG_TAG, "Started");
            saveToLog("Started", this);
            // convert to a simple array so we can pass it to the AsyncTask
            JSONObject point = null;
            double latitude = 0;
            double longitude = 0;
            double altitude = 0;
            int delay = 0;
            double old_lat = 0, old_lon = 0, distance = 0, speed = 0;
            Location loc = new Location(LOCATION_PROVIDER);
            synchronized (this) {
                try {
                    point = (JSONObject) jsonArray.get(0);
                    current_pos = point;

                    // ПЕРВАЯ ТОЧКА
                    latitude = current_pos.getDouble("lat");
                    longitude = current_pos.getDouble("lon");
                    altitude = point.getDouble("alt") + random();  // Высоту берем из точки остановки

                    loc.setLatitude(latitude + random());
                    loc.setLongitude(longitude + random());
                    loc.setAltitude(altitude);
                    loc.setAccuracy(5.0f);
                    loc.setSpeed(0.0f);
                    loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                    loc.setTime(System.currentTimeMillis());

                    // show debug message in log
                    Log.d(LOG_TAG, loc.toString());
                    saveToLog(loc.toString(), this);
                    try {
                        locationManager.setTestProviderLocation(LOCATION_PROVIDER, loc);
                    } catch (Exception e) {
                        Log.d(LOG_TAG, "ERROR : " + e.getMessage());
                        saveToLog("ERROR : " + e.getMessage(), this);
                        sendNotification("MES", "Ошибка", e.getMessage());
                    }

                    sendMessage(point.getDouble("lat"), point.getDouble("lon"), 0, 0);
                    sendNotification("MES", "Стоянка", "Точка остановки 1. Стояка " + point.getInt("time") + " мин.");
                    Log.d(LOG_TAG, "Точка остановки 1. Стояка " + point.getInt("time") + " мин.");
                    saveToLog("Точка остановки 1. Стояка " + point.getInt("time") + " мин.", this);

                    for (int t = 0; t < point.getInt("time") * 60; t++) {
                        if (!isRun) break;
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ignored) {
                        }
                        if (t % 5 == 0) {
                            // Пока стоим - показываем поступление координат вокруг точки остановки
                            latitude = point.getDouble("lat");
                            longitude = point.getDouble("lon");
                            altitude = point.getDouble("alt");  // Высоту берем из точки остановки

                            //loc = new Location(LOCATION_PROVIDER);
                            loc.setLatitude(latitude);
                            loc.setLongitude(longitude);
                            loc.setAltitude(altitude);
                            loc.setAccuracy(5.0f);
                            loc.setSpeed(0.0f);
                            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                            loc.setTime(System.currentTimeMillis());

                            // show debug message in log
                            Log.d(LOG_TAG, loc.toString());
                            saveToLog(loc.toString(), this);
                            try {
                                locationManager.setTestProviderLocation(LOCATION_PROVIDER, loc);
                            } catch (Exception e) {
                                Log.d(LOG_TAG, "ERROR : " + e.getMessage());
                                saveToLog("ERROR : " + e.getMessage(), this);
                                sendNotification("MES", "Ошибка", e.getMessage());
                            }
                            sendMessage(latitude, longitude, 0, 0);
                        }
                    }
                } catch (JSONException e) {
                    Log.d(LOG_TAG, "ERROR : " + e.getMessage());
                    saveToLog("ERROR : " + e.getMessage(), this);
                    sendNotification("MES", "Ошибка", e.getMessage());
                }

                // ДВИЖЕНИЕ
                for (int i = 1; i < jsonArray.length(); i++) {
                    try {
                        point = (JSONObject) jsonArray.get(i);
                        current_pos = point;
                    } catch (JSONException e) {
                        Log.d(LOG_TAG, "ERROR : " + e.getMessage());
                        saveToLog("ERROR : " + e.getMessage(), this);
                        sendNotification("MES", "Ошибка", e.getMessage());
                    }
                    if (point == null) {
                        Log.d(LOG_TAG, "ERROR : POINT NULL");
                        saveToLog("ERROR : POINT NULL", this);
                        sendNotification("MES", "Ошибка", "POINT NULL");
                        break;
                    }

                    try {
                        if (isRun && point.has("path")) {
                            JSONArray path_arr = point.getJSONArray("path");
                            for (int j = 0; j < path_arr.length(); j++) {
                                if (!isRun) break;
                                current_pos = (JSONObject) path_arr.get(j);

                                if (old_lat != 0 && old_lon != 0) {
                                    distance = SphericalUtil.computeDistanceBetween(new LatLng(current_pos.getDouble("lat"), current_pos.getDouble("lon")),
                                            new LatLng(old_lat, old_lon));
                                    if (distance > 200) {
                                        speed = 60f + (random() * 20f - 10f);
                                    } else if (distance > 50) {
                                        speed = 40f + (random() * 20f - 10f);
                                    } else {
                                        speed = 20f + (random() * 20f - 10f);
                                    }
                                    delay = (int) round(distance / (speed * 1000f / 3600f));
                                }
                                try {
                                    double heading = SphericalUtil.computeHeading(new LatLng(current_pos.getDouble("lat"), current_pos.getDouble("lon")),
                                            new LatLng(old_lat, old_lon));
                                    LatLng start_pos = new LatLng(old_lat, old_lon);
                                    float s = 0;
                                    for (int t = 0; t < delay; t++) {
                                        if (!isRun) break;
                                        LatLng temp_pos = SphericalUtil.computeOffset(start_pos, distance / delay, heading + 180);
                                        altitude = point.getDouble("alt");  // Высоту берем из точки остановки
                                        //loc = new Location(LOCATION_PROVIDER);
                                        loc.setLatitude(temp_pos.latitude);
                                        loc.setLongitude(temp_pos.longitude);
                                        loc.setAltitude(altitude);
                                        loc.setAccuracy(5.0f);
                                        s = (float) (speed + Math.random() * 6 - 3);
                                        loc.setSpeed((float) s * 1000f / 3600f);
                                        loc.setBearing((float) (heading + 180));
                                        loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                                        loc.setTime(System.currentTimeMillis());

                                        // show debug message in log
                                        Log.d(LOG_TAG, loc.toString());
                                        saveToLog(loc.toString(), this);
                                        try {
                                            locationManager.setTestProviderLocation(LOCATION_PROVIDER, loc);
                                        } catch (Exception e) {
                                            Log.d(LOG_TAG, "ERROR : " + e.getMessage());
                                            saveToLog("ERROR : " + e.getMessage(), this);
                                            sendNotification("MES", "Ошибка", e.getMessage());
                                        }
                                        sendMessage(temp_pos.latitude, temp_pos.longitude, s, 0);
                                        sendNotification("MES", "Движение к точке " + (i + 1), (int) s + " км/ч.");
                                        start_pos = temp_pos;
                                        Thread.sleep((long) (1000 + (Math.random() * 200 - 100)));
                                    }
                                } catch (InterruptedException ignored) {
                                }

                                //                            latitude = current_pos.getDouble("lat");
                                //                            longitude = current_pos.getDouble("lon");
                                //                            altitude = point.getDouble("alt");  // Высоту берем из точки остановки
                                //                            // translate to actual GPS location
                                //                            //loc = new Location(LOCATION_PROVIDER);
                                //                            loc.setLatitude(latitude);
                                //                            loc.setLongitude(longitude);
                                //                            loc.setAltitude(altitude);
                                //                            loc.setAccuracy(5.0f);
                                //                            loc.setSpeed((float) speed * 1000f / 3600f);
                                //                            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                                //                            loc.setTime(System.currentTimeMillis());

                                if (isRun) {
                                    // show debug message in log
                                    //                                Log.d(LOG_TAG, loc.toString());
                                    //                                saveToLog(loc.toString(), this);
                                    //                                try {
                                    //                                    locationManager.setTestProviderLocation(LOCATION_PROVIDER, loc);
                                    //                                } catch (Exception e) {
                                    //                                    Log.d(LOG_TAG, "ERROR : " + e.getMessage());
                                    //                                    saveToLog("ERROR : " + e.getMessage(), this);
                                    //                                    sendNotification("MES", "Ошибка",  e.getMessage());
                                    //                                }
                                    //                                sendMessage(current_pos.getDouble("lat"), current_pos.getDouble("lon"), speed, distance);
                                    //                                sendNotification("MES", "Движение к точке "+(i+1), (int) speed + " км/ч.");
                                    //                                Log.d(LOG_TAG, "Движение " + (int) distance + " м. " + (int) speed + " км/ч.");
                                    //                                saveToLog("Движение " + (int) distance + " м. " + (int) speed + " км/ч.", this);

                                    old_lat = current_pos.getDouble("lat");
                                    old_lon = current_pos.getDouble("lon");
                                }
                            }
                        }

                        // ТОЧКА ОСТАНОВКИ
                        if (isRun) {
                            try {
                                sendMessage(point.getDouble("lat"), point.getDouble("lon"), 0, 0);
                                sendNotification("MES", "Стоянка", "Точка остановки " + (i + 1) + ". Стояка " + point.getInt("time") + " мин.");
                                Log.d(LOG_TAG, "Точка остановки " + (i + 1) + ". Стояка " + point.getInt("time") + " мин.");
                                saveToLog("Точка остановки " + (i + 1) + ". Стояка " + point.getInt("time") + " мин.", this);

                                for (int t = 0; t < point.getInt("time") * 60; t++) {
                                    if (!isRun) break;
                                    try {
                                        Thread.sleep(1000);
                                    } catch (InterruptedException ignored) {
                                    }
                                    if (t % 5 == 0) {
                                        // Пока стоим - показываем поступление координат вокруг точки остановки
                                        latitude = point.getDouble("lat");
                                        longitude = point.getDouble("lon");
                                        altitude = point.getDouble("alt");  // Высоту берем из точки остановки

                                        //loc = new Location(LOCATION_PROVIDER);
                                        loc.setLatitude(latitude);
                                        loc.setLongitude(longitude);
                                        loc.setAltitude(altitude);
                                        loc.setAccuracy(5.0f);
                                        loc.setSpeed(0.0f);
                                        loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                                        loc.setTime(System.currentTimeMillis());

                                        // show debug message in log
                                        Log.d(LOG_TAG, loc.toString());
                                        saveToLog(loc.toString(), this);
                                        try {
                                            locationManager.setTestProviderLocation(LOCATION_PROVIDER, loc);
                                        } catch (Exception e) {
                                            Log.d(LOG_TAG, "ERROR : " + e.getMessage());
                                            saveToLog("ERROR : " + e.getMessage(), this);
                                            sendNotification("MES", "Ошибка", e.getMessage());
                                        }
                                        sendMessage(latitude, longitude, 0, 0);
                                    }
                                }
                            } catch (JSONException e) {
                                Log.d(LOG_TAG, "ERROR : " + e.getMessage());
                                saveToLog("ERROR : " + e.getMessage(), this);
                                sendNotification("MES", "Ошибка", e.getMessage());
                            }
                        }
                    } catch (JSONException e) {
                        Log.d(LOG_TAG, "ERROR : " + e.getMessage());
                        saveToLog("ERROR : " + e.getMessage(), this);
                        sendNotification("MES", "Ошибка", e.getMessage());
                    }
                }
            }
            stopSelf();
            sendNotification("MES", "MES", "Остановлен");
            saveToLog("Остановлен", this);
            Log.d(LOG_TAG, "Остановлен");
            isRun = false;
            Intent intent = new Intent(MESSAGE_MES);
            intent.putExtra("name", "stop");
            sendBroadcast(intent);
        });
        task.setDaemon(true);
        task.setPriority(Thread.MAX_PRIORITY);
        task.start();
    }
}
