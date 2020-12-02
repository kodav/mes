package com.stelife.mes;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Point;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.InputType;
import android.util.Log;
import android.view.Display;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.Toast;

import com.google.android.gms.maps.CameraUpdate;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.google.maps.DirectionsApi;
import com.google.maps.DirectionsApiRequest;
import com.google.maps.ElevationApi;
import com.google.maps.GeoApiContext;
import com.google.maps.android.SphericalUtil;
import com.google.maps.errors.ApiException;
import com.google.maps.model.DirectionsResult;
import com.google.maps.model.ElevationResult;
import com.google.maps.model.TravelMode;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.stelife.mes.Common.JSON_FILE;
import static com.stelife.mes.Common.LOG_FILE;
import static com.stelife.mes.Common.LOG_TAG;
import static com.stelife.mes.Common.saveToLog;
import static com.stelife.mes.LService.DEFAULT_NOTIFICATION_ID;
import static com.stelife.mes.LService.LOCATION_PROVIDER;
import static com.stelife.mes.LService.MESSAGE_MES;
import static com.stelife.mes.LService.NOTIFICATION_CHANNEL_ID;
import static com.stelife.mes.LService.channelName;
import static java.lang.Math.random;
import static java.lang.Math.round;

public class MainActivity extends AppCompatActivity implements OnMapReadyCallback {
    private boolean isBound;
    LService mService;
    private static GoogleMap mMap;
    private LatLng new_latLng;
    ArrayList<JSONObject> points = new ArrayList<>();
    Polyline path;
    static Marker cur_marker = null;
    private MsgReceiver mMsgReceiver;
    Intent service_intent;
    Menu menu;
    String device_id;
    private int found;
//    static PendingIntent contentIntent;
//    static NotificationManager notificationManager;
//    static Thread task;

    @RequiresApi(api = Build.VERSION_CODES.P)
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if ((ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) ||
             (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) ||
             (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) ||
             (ActivityCompat.checkSelfPermission(this, Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) != PackageManager.PERMISSION_GRANTED) ||
             (ActivityCompat.checkSelfPermission(this, Manifest.permission.WAKE_LOCK) != PackageManager.PERMISSION_GRANTED)){
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(new String[]{Manifest.permission.FOREGROUND_SERVICE,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.READ_PHONE_STATE,
                        Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Manifest.permission.WAKE_LOCK
                }, 0);
            }
        }

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }

        TelephonyManager telephonyManager = (TelephonyManager)getSystemService(Context.TELEPHONY_SERVICE);
        if (telephonyManager != null) {
            device_id = telephonyManager.getImei();
            Log.d(LOG_TAG, device_id);
            saveToLog(device_id, this);
        }

        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        assert mapFragment != null;
        mapFragment.getMapAsync(this);

        service_intent = new Intent(this, LService.class);
        bindService(service_intent, serviceConnection, Context.BIND_AUTO_CREATE);
//        createNotification();
        checkIMEI(false);
    }

    private void checkIMEI(boolean service) {
        new Thread() {
            @Override
            public void run() {
                try {
                    HttpTransport transport = AndroidHttp.newCompatibleTransport();
                    JsonFactory factory = JacksonFactory.getDefaultInstance();
                    final Sheets sheetsService = new Sheets.Builder(transport, factory, null)
                            .setApplicationName("com.stelife.mes")
                            .build();
                    final String spreadsheetId = "1lDGsiwnlp6gwxiVs505t8WTib13vZpWW3xaOQYsGwrk";

                    String range = "Лист1!A:B";
                    ValueRange result = null;
                    try {
                        result = sheetsService.spreadsheets().values()
                                .get(spreadsheetId, range)
                                .setKey(getResources().getString(R.string.google_maps_key))
                                .execute();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    found = 0;
                    if (result != null) {
                        for (int r = 0; r < result.getValues().size(); r++) {
                            List<Object> row = result.getValues().get(r);
                            if (row.get(0).toString().equals(device_id)) {
                                try {
                                    found = Integer.parseInt(row.get(1).toString());
                                } catch (Exception e){
                                    found = 0;
                                }
                                break;
                            }
                        }
                    }
                    if (found > 0) {
                        Log.d(LOG_TAG, "found IMEI "+found+" days");
                        saveToLog("found IMEI "+found+" days", MainActivity.this);
                        if (service) {
//                            ContextCompat.startForegroundService(MainActivity.this, service_intent);
                            startService(service_intent);
//                            testTask();
                            showMenu(true);
                        } else {
                            showToast("Осталось дней: " + found);
                        }
                    } else {
                        Log.d(LOG_TAG, "not found IMEI");
                        saveToLog("not found IMEI", MainActivity.this);
                        showToast("Нет доступа");
                        finish();
                    }
                }
                catch (Exception e) {
                    Log.e(LOG_TAG, Objects.requireNonNull(e.getMessage()));
                }
            }
        }.start();
    }

    private void showMenu(boolean b) {
        runOnUiThread(() -> {
            menu.findItem(R.id.start).setVisible(!b);
            menu.findItem(R.id.stop).setVisible(b);
        });
    }

    public void showToast(final String toast)
    {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, toast, Toast.LENGTH_SHORT).show());
    }

    // Здесь необходимо создать соединение с сервисом
    ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            // используем mService экземпляр класса для доступа к публичному LocalService
            LService.LocalService localService = (LService.LocalService) service;
            mService = localService.getService();
            if (mService != null && mService.isStart()) {
                try {
                    LatLng latLng = mService.getCurrentPos();
                    setCurrentMarker(latLng, 0, 0);
                } catch (JSONException ignore) {
                }
            }
            saveToLog("onServiceConnected", MainActivity.this);
            isBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            saveToLog("onServiceDisconnected", MainActivity.this);
            isBound = false;
        }
    };

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        this.menu = menu;
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu, menu);
        if (mService == null) {
            menu.findItem(R.id.start).setVisible(true);
            menu.findItem(R.id.stop).setVisible(false);
        } else {
            showMenu(mService.isStart());
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Intent intent = new Intent();
        // Операции для выбранного пункта меню
        switch (item.getItemId())
        {
            case R.id.start:
                AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                builder.setTitle("GPS");
                builder.setMessage("Уверены что GPS отключен?");
                builder.setPositiveButton("Да", (dialog, which) -> {
                    checkIMEI(true);
                    dialog.dismiss();
                });
                builder.setNegativeButton("Нет", (dialog, which) -> {
                    startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                    dialog.dismiss();
                });
                AlertDialog alert = builder.create();
                alert.show();
                return true;
            case R.id.stop:
                if (mService != null) {
                    mService.Stop();
                }
//                task.interrupt();
                //stopService(new Intent(this, LService.class));
                showMenu(false);
                if (cur_marker != null){
                    cur_marker.remove();
                    cur_marker = null;
                }
                return true;
            case R.id.share:
                intent.setAction(Intent.ACTION_SEND);
                intent.setType("text/plain");
                Uri jsonUri = FileProvider.getUriForFile(
                        MainActivity.this,
                        "com.stelife.mes.provider", //(use your app signature + ".provider" )
                        new File(this.getFilesDir()+"/"+ JSON_FILE));

                intent.putExtra(Intent.EXTRA_STREAM, jsonUri);
                startActivity(intent);
                return true;
            case R.id.sendlog:
                intent.setAction(Intent.ACTION_SEND);
                intent.setType("text/plain");
                Uri logUri = FileProvider.getUriForFile(
                        MainActivity.this,
                        "com.stelife.mes.provider", //(use your app signature + ".provider" )
                        new File(this.getFilesDir()+"/"+ LOG_FILE));

                intent.putExtra(Intent.EXTRA_STREAM, logUri);
                startActivity(intent);
                return true;
            case R.id.dellog:
                new File(this.getFilesDir()+"/"+ LOG_FILE).delete();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onDestroy() {
        saveToLog("Destroy activity", this);
        super.onDestroy();
        if (isBound) {
            unbindService(serviceConnection);
        }
        cur_marker = null;
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;
        mMap.setOnMapClickListener(latLng -> {
            boolean isrun = false;
            if (mService != null) {
                isrun = mService.isStart();
            }
            if (!isrun){
                new_latLng = latLng;
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("Новая метка");
                builder.setMessage("Длительность стоянки (мин)");
                final EditText input = new EditText(this);
                input.setInputType(InputType.TYPE_CLASS_NUMBER);
                builder.setView(input);
                builder.setPositiveButton("OK", (dialog, which) -> {
                    int t = 0;
                    try {
                        t = Integer.parseInt(input.getText().toString());
                    } catch (Exception e){
                        t = -1;
                    }
                    if (t < 0){
                        Toast.makeText(MainActivity.this, "Ошибка ввода", Toast.LENGTH_LONG).show();
                    } else {
                        setNewMarker(t);
                    }
                });
                builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
                builder.show();
            }
        });

        mMap.setOnMarkerClickListener(marker -> {
            boolean isrun = false;
            if (mService != null) {
                isrun = mService.isStart();
            }
            if (!isrun){
                AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                builder.setTitle("Метка " + marker.getTitle());
                builder.setMessage("Удалить?");
                builder.setPositiveButton("Да", (dialog, which) -> {
                    for (JSONObject o : points) {
                        if (o.hashCode() == (int) marker.getTag()) {
                            marker.remove();
                            points.remove(o);
                            try {
                                getPath();
                                SaveJSON();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            break;
                        }
                    }
                    dialog.dismiss();
                });
                builder.setNegativeButton("Нет", (dialog, which) -> dialog.dismiss());
                AlertDialog alert = builder.create();
                alert.show();
            }
            return false;
        });

        LatLngBounds latLngBounds = ReadJSON();
        if (latLngBounds != null){
            //Выставляем камеру по всему треку
            Display display = getWindowManager().getDefaultDisplay();
            Point size = new Point();
            display.getSize(size);
            CameraUpdate track = CameraUpdateFactory.newLatLngBounds(latLngBounds, size.x, size.x, 25);
            mMap.moveCamera(track);
        } else {
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(53.3547792f, 83.7697832f), 10.0f));
        }
        //DrawPath(true);
    }

    private void setCurrentMarker(LatLng latLng, double speed, double distance) {
        if (cur_marker == null){
            MarkerOptions markerOptions = new MarkerOptions().
                    icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)).
                    zIndex(100).
                    title((int)speed+" км/ч").
                    position(latLng);
            cur_marker = mMap.addMarker(markerOptions);
        } else {
            cur_marker.setTitle((int)speed+" км/ч");
            cur_marker.setPosition(latLng);
            cur_marker.showInfoWindow();
            getApplicationContext();
        }
    }

    private void getPath() {
        List<com.google.maps.model.LatLng> linepath = new ArrayList<>();

        if (path != null)
            path.remove();
        if (points.size() > 1) {
            GeoApiContext geoApiContext = new GeoApiContext.Builder()
                    .apiKey(getResources().getString(R.string.google_maps_key))
                    .build();
            //Здесь будет наш итоговый путь состоящий из набора точек
            DirectionsResult result = null;
            try {
                if (points.size() > 1) {
                    for (int i = 0; i < points.size()-1; i++) {
                        DirectionsApiRequest api = DirectionsApi.newRequest(geoApiContext);
                        api.mode(TravelMode.DRIVING);
//                        api.alternatives(true);
//                        api.avoid(DirectionsApi.RouteRestriction.TOLLS, DirectionsApi.RouteRestriction.FERRIES);
                        //Место старта
                        api.origin(new com.google.maps.model.LatLng(points.get(i).getDouble("lat"), points.get(i).getDouble("lon")));
                        //Место окончания
                        JSONObject point = points.get(i+1);
                        point.remove("path");
                        api.destination(new com.google.maps.model.LatLng(point.getDouble("lat"), point.getDouble("lon")));
                        // Рассчитаем путь от точки до точки
                        result = api.await();
                        if (result != null && result.routes != null) {
                            List<com.google.maps.model.LatLng> path = result.routes[0].overviewPolyline.decodePath();
                            JSONArray path_arr = new JSONArray();
                            for (int t=0; t < path.size(); t++) {
                                JSONObject o = new JSONObject();
                                com.google.maps.model.LatLng p = path.get(t);
                                o.put("lat", p.lat);
                                o.put("lon", p.lng);
                                path_arr.put(o);
                                linepath.add(p);
                            }
                            linepath.add(new com.google.maps.model.LatLng(point.getDouble("lat"), point.getDouble("lon")));
                            point.put("path", path_arr);
                        }
                        points.set(i + 1, point);
                    }
                }
            } catch (ApiException | InterruptedException | IOException | JSONException e) {
                Log.d(LOG_TAG, Objects.requireNonNull(e.getMessage()));
                saveToLog(Objects.requireNonNull(e.getMessage()), this);
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            }

            //Преобразование итогового пути в набор точек
            if (linepath.size() > 0) {
                //Линия которую будем рисовать
                PolylineOptions line = new PolylineOptions();
                LatLngBounds.Builder latLngBuilder = new LatLngBounds.Builder();
                // Проходимся по всем точкам, добавляем их в Polyline и в LanLngBounds.Builder
                for (int i = 0; i < linepath.size(); i++) {
                    line.add(new com.google.android.gms.maps.model.LatLng(linepath.get(i).lat, linepath.get(i).lng));
                    latLngBuilder.include(new com.google.android.gms.maps.model.LatLng(linepath.get(i).lat, linepath.get(i).lng));
                }

                //Делаем линию более менее симпатичное
                line.width(12f).color(R.color.colorPrimary);

                //Добавляем линию на карту
                path = mMap.addPolyline(line);
            }
        }
    }

    private LatLngBounds ReadJSON(){
        points.clear();
        JSONArray jsonArray = null;
        //Линия которую будем рисовать
        PolylineOptions line = new PolylineOptions();
        LatLngBounds.Builder latLngBuilder = new LatLngBounds.Builder();
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

                JSONObject point;
                for (int i=0; i < jsonArray.length(); i++) {
                    try {
                        point = (JSONObject) jsonArray.get(i);
                        LatLng ll_point = new LatLng(point.getDouble("lat"), point.getDouble("lon"));
                        points.add(point);
                        MarkerOptions markerOptions = new MarkerOptions().
                                position(ll_point).
                                title(point.getInt("time") + " мин.");
                        // Add a marker
                        mMap.addMarker(markerOptions).setTag(point.hashCode());
                        if (point.has("path")) {
                            JSONArray path_arr = point.getJSONArray("path");
                            for (int j = 0; j < path_arr.length(); j++) {
                                JSONObject o = (JSONObject) path_arr.get(j);
                                LatLng ll = new LatLng(o.getDouble("lat"), o.getDouble("lon"));
                                line.add(ll);
                                latLngBuilder.include(ll);
                            }
                            line.add(ll_point);
                            latLngBuilder.include(ll_point);
                        }
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
                //Делаем линию более менее симпатичное
                line.width(12f).color(R.color.colorPrimary);
                //Добавляем линию на карту
                path = mMap.addPolyline(line);
            }
        }

        catch (Exception ignored) {}
        if (line.getPoints().size() == 0){
            return null;
        } else {
            return latLngBuilder.build();
        }
    }

    private void SaveJSON() throws IOException {
        JSONObject root = new JSONObject();
        JSONArray jsonArray = new JSONArray();
        for (JSONObject o : points) {
            jsonArray.put(o);
        }
//        Collections.sort(points, (lhs, rhs) -> {
//            try {
//                return (lhs.getString("time").toLowerCase().compareTo(rhs.getString("time").toLowerCase()));
//            } catch (JSONException e) {
//                // TODO Auto-generated catch block
//                e.printStackTrace();
//                return 0;
//            }
//        });

        try {
            root.put("coords", jsonArray);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        //gives file name
        FileOutputStream output = openFileOutput(JSON_FILE, Context.MODE_PRIVATE);
        //creates new StreamWriter
        OutputStreamWriter writer = new OutputStreamWriter(output);
        //writes json with file name story.json
        writer.write(root.toString());
        writer.flush();
        //closes writer
        writer.close();
    }

    // установка нового маркера с временем
    private void setNewMarker(int time) {
        JSONObject jsonObject = new JSONObject();
        try{
            jsonObject.put("lat",new_latLng.latitude);
            jsonObject.put("lon",new_latLng.longitude);
            double altitude = 0;
            GeoApiContext geoApiContext = new GeoApiContext.Builder()
                    .apiKey(getResources().getString(R.string.google_maps_key))
                    .build();
            try {
                ElevationResult result = ElevationApi.getByPoint(geoApiContext, new com.google.maps.model.LatLng(new_latLng.latitude, new_latLng.longitude)).await();
                altitude = result.elevation;
            } catch (ApiException | InterruptedException | IOException e) {
                e.printStackTrace();
            }
            jsonObject.put("alt",altitude);
            jsonObject.put("time", time);

            // Add a marker
            MarkerOptions markerOptions = new MarkerOptions().
                    position(new_latLng).
                    title(time+" мин.");
            mMap.addMarker(markerOptions).setTag(jsonObject.hashCode());
            points.add(jsonObject);
        } catch (Exception e){
            e.printStackTrace();
        }
        getPath();
        try {
            SaveJSON();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        saveToLog("Resume activity", this);
        IntentFilter filter = new IntentFilter("com.stelife.mes.message");
        mMsgReceiver = new MsgReceiver();
        registerReceiver(mMsgReceiver, filter);
    }

    @Override
    public void onPause() {
        super.onPause();
        saveToLog("Pause activity", this);
        unregisterReceiver(mMsgReceiver);
    }

    public class MsgReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (Objects.requireNonNull(intent.getStringExtra("name"))){
                case "pos" :
                    LatLng latLng = new LatLng(intent.getDoubleExtra("latitude", 0), intent.getDoubleExtra("longitude", 0));
                    setCurrentMarker(latLng, intent.getDoubleExtra("speed", 0), intent.getDoubleExtra("distance", 0));
                    break;
                case "stop":
                    stopService(new Intent(MainActivity.this, LService.class));
                    showMenu(false);
                    if (cur_marker != null){
                        cur_marker.remove();
                        cur_marker = null;
                    }
                    break;
                case "gps_mock" :
                    Toast.makeText(MainActivity.this, intent.getStringExtra("message"), Toast.LENGTH_LONG).show();
                    stopService(new Intent(MainActivity.this, LService.class));
                    showMenu(false);
                    if (cur_marker != null){
                        cur_marker.remove();
                        cur_marker = null;
                    }
                    try {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
                    } catch (Exception e){
                        Toast.makeText(MainActivity.this, e.getLocalizedMessage(), Toast.LENGTH_LONG).show();
                    }
                    break;
                case "error":
                    Toast.makeText(MainActivity.this, intent.getStringExtra("message"), Toast.LENGTH_LONG).show();
                    stopService(new Intent(MainActivity.this, LService.class));
                    showMenu(false);
                    if (cur_marker != null){
                        cur_marker.remove();
                        cur_marker = null;
                    }
                    break;
            }
        }
    }
/*
    void createNotification(){
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setAction(Intent.ACTION_MAIN);
        notificationIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        contentIntent = PendingIntent.getActivity(getApplicationContext(), 0, notificationIntent, 0);//PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationChannel chan = new NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_DEFAULT);
        chan.setLightColor(Color.BLUE);
        chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        notificationManager.createNotificationChannel(chan);
    }

    public void sendNotification(String Ticker, String Title, String Text) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID);
        builder.setContentIntent(contentIntent)
                .setOngoing(true)   //invulnerable
                .setTicker(Ticker)
                .setContentTitle(Title)
                .setContentText(Text)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setSmallIcon(R.mipmap.ic_launcher_foreground);
        //.setWhen(System.currentTimeMillis());
        notificationManager.notify(DEFAULT_NOTIFICATION_ID, builder.build());
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

    void testTask(){
        task = new Thread(() -> {
            JSONObject current_pos;
            boolean isRun = true;
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
            sendNotification("MES", "MES", "Остановлен");
            saveToLog("Остановлен", this);
            Log.d(LOG_TAG, "Остановлен");
            isRun = false;
            Intent intent = new Intent(MESSAGE_MES);
            intent.putExtra("name", "stop");
            sendBroadcast(intent);
        });

        task.start();
    }
 */
}