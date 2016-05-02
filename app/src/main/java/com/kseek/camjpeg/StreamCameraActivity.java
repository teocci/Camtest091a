package com.kseek.camjpeg;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.preference.PreferenceManager;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.TextView;

import com.kseek.camjpeg.net.http.MJpegHttpStreamer;
import com.kseek.camjpeg.utils.PreferenceHelper;
import com.kseek.camjpeg.utils.Utilities;

import java.util.Locale;

import static com.kseek.camjpeg.utils.Utilities.LogI;

public final class StreamCameraActivity extends Activity
        implements SurfaceHolder.Callback
{
    private static final String TAG = StreamCameraActivity.class.getSimpleName();

    private static final String WAKE_LOCK_TAG = "camjpeg";

    private SurfaceHolder previewDisplay = null;
    private CameraStreamer cameraStreamer = null;

    public TextView statusTextView = null;

    private StreamCameraActivity mainActivity = this;

    //private ImageGraph mImageGraph;

    private PreferenceHelper preferenceHelper;
    private SharedPreferences sharedPreferences;

    private CamjpegApplication mainApplication;


    private MenuItem mSettingsMenuItem = null;
    private WakeLock wakeLock = null;


    public static boolean isRunning = false;

    public static long lastMotionTime = 0;
    public static long lastMotionKeepAliveTime = 0;

    private boolean running = false;
    private boolean previewDisplayCreated = false;


    private int cameraIndex = 0;
    private boolean useFlashLight = false;

    private int jpegQuality = 80;
    // preview sizes will always have at least one element, so this is safe
    private int previewSizeIndex = 0;



    private int width = 320;
    private int height = 240;

    static int httpPort = 8080;


    public StreamCameraActivity()
    {
        super();
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState)
    {
        // load and setup GUI
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        mainApplication = (CamjpegApplication) getApplication();

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        preferenceHelper = new PreferenceHelper(this, sharedPreferences);

        new LoadPreferencesTask().execute();

        statusTextView = (TextView)findViewById(R.id.tv_status);

        previewDisplay = ((SurfaceView) findViewById(R.id.camera)).getHolder();
        previewDisplay.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        previewDisplay.addCallback(this);

        updatePrefCacheAndUi();

        final PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, WAKE_LOCK_TAG);

    }

    @Override
    protected void onResume()
    {
        super.onResume();
        running = true;
        isRunning = true;

        if (sharedPreferences != null) {
            sharedPreferences.registerOnSharedPreferenceChangeListener(mSharedPreferenceListener);
        }

        lastMotionKeepAliveTime = System.currentTimeMillis();
        updatePrefCacheAndUi();
        tryStartCameraStreamer();
        wakeLock.acquire();
    }

    @Override
    protected void onPause()
    {
        releaseLocks();
        super.onPause();
        running = false;
        isRunning = false;

        if (sharedPreferences != null) {
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(mSharedPreferenceListener);
        }

        ensureCameraStreamerStopped();
    }

    @Override
    public void surfaceChanged(final SurfaceHolder holder, final int format,
                               final int width, final int height)
    {
        // Ingored
    }

    @Override
    public void surfaceCreated(final SurfaceHolder holder)
    {
        previewDisplayCreated = true;
        tryStartCameraStreamer();
    }

    @Override
    public void surfaceDestroyed(final SurfaceHolder holder)
    {
        previewDisplayCreated = false;
        ensureCameraStreamerStopped();
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu)
    {
        super.onCreateOptionsMenu(menu);
        mSettingsMenuItem = menu.add(R.string.settings);
        mSettingsMenuItem.setIcon(android.R.drawable.ic_menu_manage);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item)
    {
        if (item != mSettingsMenuItem) {
            return super.onOptionsItemSelected(item);
        }
        startActivity(new Intent(this, CamjpegPreferenceActivity.class));
        return true;
    }

    protected void getPreferedResolution()
    {
        String resolutionString =
                preferenceHelper
                        .stringPreference(R.string.key_pref_resolution, "320x240");
        String WnH[] = resolutionString.split("x");
        width = Integer.valueOf(WnH[0]);
        height = Integer.valueOf(WnH[1]);
    }

    public void releaseLocks()
    {
        if (wakeLock != null) {
            // release lock for preview
            PowerManager.WakeLock tempWL = wakeLock;

            LogI("CamActivity WakeLock released!");

            if (tempWL.isHeld())
                tempWL.release();
        }
    }

    private void tryStartCameraStreamer()
    {
        if (running && previewDisplayCreated && sharedPreferences != null) {
            cameraStreamer = new CameraStreamer(cameraIndex,
                    useFlashLight,
                    httpPort,
                    previewSizeIndex,
                    jpegQuality,
                    previewDisplay,
                    width,
                    height);
            cameraStreamer.start();
        }
    }

    private void ensureCameraStreamerStopped()
    {
        if (cameraStreamer != null) {
            cameraStreamer.stop();
            cameraStreamer = null;
        }
    }

    private final class LoadPreferencesTask
            extends AsyncTask<Void, Void, SharedPreferences>
    {
        private LoadPreferencesTask()
        {
            super();
        } // constructor()

        @Override
        protected SharedPreferences doInBackground(final Void... noParams)
        {
            return PreferenceManager.getDefaultSharedPreferences(
                    StreamCameraActivity.this);
        }

        @Override
        protected void onPostExecute(final SharedPreferences prefs)
        {
            StreamCameraActivity.this.sharedPreferences = prefs;
            prefs.registerOnSharedPreferenceChangeListener(
                    mSharedPreferenceListener);
            updatePrefCacheAndUi();
            tryStartCameraStreamer();
        }
    }

    private final OnSharedPreferenceChangeListener mSharedPreferenceListener =
            new OnSharedPreferenceChangeListener()
            {
                @Override
                public void onSharedPreferenceChanged(final SharedPreferences prefs,
                                                      final String key)
                {
                    updatePrefCacheAndUi();
                } // onSharedPreferenceChanged(SharedPreferences, String)

            }; // mSharedPreferencesListener

    private final int getPrefInt(final String key, final int defValue)
    {
        // We can't just call getInt because the preference activity
        // saves everything as a string.
        try {
            return Integer.parseInt(sharedPreferences.getString(key, null));
        } catch (final NullPointerException e) {
            return defValue;
        } catch (final NumberFormatException e) {
            return defValue;
        }
    }

    private final void updatePrefCacheAndUi()
    {
        cameraIndex = preferenceHelper
                .booleanPreference(R.string.key_pref_camera_index_def, false) ? 0 : 1;

        useFlashLight = (!hasFlashLight()) ? false : preferenceHelper
                .booleanPreference(R.string.key_pref_flash_light_def, false);

        httpPort = Integer.valueOf(preferenceHelper
                .intPreference(R.string.key_pref_http_local_port,
                        8080));

        // The port must be in the range [1024 65535]
        if (httpPort < 1024)
            httpPort = 1024;
        else if (httpPort > 65535)
            httpPort = 65535;


        previewSizeIndex = preferenceHelper
                .intPreference(R.string.key_pref_preview_size_index_def,
                        previewSizeIndex);

        jpegQuality = preferenceHelper
                .intPreference(R.string.key_pref_jpeg_quality,
                jpegQuality);

        // The JPEG quality must be in the range [0 100]
        if (jpegQuality < 0) {
            jpegQuality = 0;
        } else if (jpegQuality > 100) {
            jpegQuality = 100;
        }

        displayIpAddress();
        getPreferedResolution();
        //mIpAddressView.setText("http://" + ipAddress + ":" + httpPort + "/");
    }

    //Will display the view with the valid IP listener.
    private void displayIpAddress()
    {
        TextView tv = (TextView) findViewById(R.id.tv_message);

        WifiManager wifiManager = (WifiManager) mainApplication.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wifiManager.getConnectionInfo();
        String ip;

        if (info != null && info.getNetworkId() > -1) {
            int i = info.getIpAddress();
            ip = String.format(Locale.ENGLISH, "%d.%d.%d.%d",
                    i & 0xff,
                    i >> 8 & 0xff,
                    i >> 16 & 0xff,
                    i >> 24 & 0xff);

            tv.setText(MJpegHttpStreamer.isHttpEnabled() ? "http://" : "");
            tv.append(ip);
            tv.append(":" + MJpegHttpStreamer.getHttpPort());
            //streamingState(0);
        } else if ((ip = Utilities.getLocalIpAddress(true)) != null) {
            tv.setText(MJpegHttpStreamer.isHttpEnabled() ? "http://" : "");
            tv.append(ip);
            tv.append(":" + MJpegHttpStreamer.getHttpPort());
            //streamingState(0);
        } else {
            //streamingState(2);
        }

    }

    private boolean hasFlashLight()
    {
        return getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_CAMERA_FLASH);
    }


}

