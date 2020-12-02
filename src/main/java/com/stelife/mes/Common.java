package com.stelife.mes;

import android.content.Context;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Objects;


class Common {
    static final String LOG_TAG = "MES_STELIFE";
    static final String JSON_FILE = "story.json";
    static final String LOG_FILE = "mes.log";

    static void saveToLog(String msg, Context context) {
        if (msg == null) return;

        String filePath = context.getFilesDir()+"/"+LOG_FILE;
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

        //Log.d(LOG_TAG, msg);
        try {
            FileWriter writer = new FileWriter(filePath, true);
            BufferedWriter bufferWriter = new BufferedWriter(writer);
            bufferWriter.write(df.format(Calendar.getInstance().getTime()) + ": " + msg + "\n");
            bufferWriter.close();
        }
        catch (IOException e) {
            Log.e(LOG_TAG, Objects.requireNonNull(e.getMessage()));
        }
    }
}
