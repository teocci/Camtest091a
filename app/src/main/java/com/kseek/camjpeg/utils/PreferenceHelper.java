package com.kseek.camjpeg.utils;

/**
 * Created by teocci on 4/29/16.
 */
import android.content.Context;
import android.content.SharedPreferences;

public class PreferenceHelper {
    Context context;
    SharedPreferences sharedPreferences;

    public String stringPreference(int keyId, String defaultValue) {
        String key = context.getString(keyId);
        return sharedPreferences.getString(key, defaultValue);
    }

    public int intPreference(int keyId, Integer defaultValue) {
        String key = context.getString(keyId);
        String stringValue = sharedPreferences.getString(key,
                defaultValue.toString());
        return Integer.valueOf(stringValue);
    }

    public boolean booleanPreference(int keyId, Boolean defaultValue) {
        String key = context.getString(keyId);
        return sharedPreferences.getBoolean(key, defaultValue);
    }

    public PreferenceHelper(Context context,
                            SharedPreferences sharedPreferences)
    {
        this.context = context;
        this.sharedPreferences = sharedPreferences;
    }
}