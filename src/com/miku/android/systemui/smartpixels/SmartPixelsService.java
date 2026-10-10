/*
 * Copyright (c) 2015, Sergii Pylypenko
 *           (c) 2018, Joe Maples
 *           (c) 2018, Adin Kwok
 *           (c) 2018, CarbonROM
 *           (c) 2026, Miku UI
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * * Neither the name of screen-dimmer-pixel-filter nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */

package com.miku.android.systemui.smartpixels;

import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.WindowManager;
import android.widget.ImageView;

public class SmartPixelsService extends Service implements DisplayManager.DisplayListener {
    public static final String LOG = "SmartPixelsService";

    public static boolean running = false;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final SparseArray<Overlay> mOverlays = new SparseArray<>();

    private DisplayManager mDisplayManager;
    private Bitmap mBmp;
    private boolean mDestroyed = false;
    private int mStartCounter = 0;

    private int mPattern = 3;
    private int mShiftTimeout = 4;

    private static final class Overlay {
        final WindowManager windowManager;
        final ImageView view;

        Overlay(WindowManager windowManager, ImageView view) {
            this.windowManager = windowManager;
            this.view = view;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        updateSettings();
        Log.d(LOG, "Service started");
        startFilter();
    }

    public void startFilter() {
        if (mBmp != null) {
            return;
        }

        mBmp = Bitmap.createBitmap(Grids.GridSideSize, Grids.GridSideSize, Bitmap.Config.ARGB_8888);
        mBmp.setDensity(Bitmap.DENSITY_NONE);
        updatePattern();

        mDisplayManager = getSystemService(DisplayManager.class);
        for (Display display : mDisplayManager.getDisplays()) {
            addOverlay(display);
        }
        mDisplayManager.registerDisplayListener(this, mHandler);

        mStartCounter++;
        final int handlerStartCounter = mStartCounter;
        final PowerManager pm = getSystemService(PowerManager.class);
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mBmp == null || mDestroyed || handlerStartCounter != mStartCounter) {
                    return;
                } else if (pm.isInteractive()) {
                    updatePattern();
                    invalidateOverlays();
                }
                if (!mDestroyed) {
                    mHandler.postDelayed(this, Grids.ShiftTimeouts[mShiftTimeout]);
                }
            }
        }, Grids.ShiftTimeouts[mShiftTimeout]);
    }

    public void stopFilter() {
        mStartCounter++;
        if (mDisplayManager != null) {
            mDisplayManager.unregisterDisplayListener(this);
        }
        for (int i = 0; i < mOverlays.size(); i++) {
            removeOverlayView(mOverlays.valueAt(i));
        }
        mOverlays.clear();
        mBmp = null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mDestroyed = true;
        stopFilter();
        Log.d(LOG, "Service stopped");
        running = false;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Log.d(LOG, "Configuration changed, updating window layout");
        for (int i = 0; i < mOverlays.size(); i++) {
            Overlay overlay = mOverlays.valueAt(i);
            try {
                overlay.windowManager.updateViewLayout(overlay.view, getLayoutParams());
            } catch (RuntimeException e) {
                Log.w(LOG, "Failed to update overlay layout", e);
            }
        }
    }

    @Override
    public void onDisplayAdded(int displayId) {
        Display display = mDisplayManager.getDisplay(displayId);
        if (display != null) {
            addOverlay(display);
        }
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        Overlay overlay = mOverlays.get(displayId);
        if (overlay != null) {
            removeOverlayView(overlay);
            mOverlays.remove(displayId);
        }
    }

    @Override
    public void onDisplayChanged(int displayId) {
        Overlay overlay = mOverlays.get(displayId);
        if (overlay != null) {
            try {
                overlay.windowManager.updateViewLayout(overlay.view, getLayoutParams());
            } catch (RuntimeException e) {
                Log.w(LOG, "Failed to update overlay on display " + displayId, e);
            }
            return;
        }
        Display display = mDisplayManager.getDisplay(displayId);
        if (display != null) {
            addOverlay(display);
        }
    }

    private void addOverlay(Display display) {
        if (mDestroyed || mBmp == null || mOverlays.indexOfKey(display.getDisplayId()) >= 0) {
            return;
        }
        try {
            Context windowContext = createWindowContext(display,
                    WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY, null);
            WindowManager windowManager = windowContext.getSystemService(WindowManager.class);
            ImageView view = new ImageView(windowContext);
            view.setBackground(createPatternDrawable());
            windowManager.addView(view, getLayoutParams());
            mOverlays.put(display.getDisplayId(), new Overlay(windowManager, view));
            Log.d(LOG, "Added overlay on display " + display.getDisplayId());
        } catch (RuntimeException e) {
            Log.e(LOG, "Failed to add overlay on display " + display.getDisplayId(), e);
        }
    }

    private void removeOverlayView(Overlay overlay) {
        try {
            overlay.windowManager.removeView(overlay.view);
        } catch (RuntimeException e) {
            Log.w(LOG, "Failed to remove overlay", e);
        }
    }

    private void invalidateOverlays() {
        for (int i = 0; i < mOverlays.size(); i++) {
            mOverlays.valueAt(i).view.invalidate();
        }
    }

    private BitmapDrawable createPatternDrawable() {
        BitmapDrawable draw = new BitmapDrawable(getResources(), mBmp);
        draw.setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        draw.setFilterBitmap(false);
        draw.setAntiAlias(false);
        draw.setTargetDensity(Bitmap.DENSITY_NONE);
        return draw;
    }

    private static WindowManager.LayoutParams getLayoutParams() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        params.privateFlags |= PRIVATE_FLAG_TRUSTED_OVERLAY
                | WindowManager.LayoutParams.PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY
                | WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        params.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setFitInsetsTypes(0);
        params.setTitle("SmartPixels");
        params.dimAmount = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        return params;
    }

    private int getShift() {
        long shift = (System.currentTimeMillis() / Grids.ShiftTimeouts[mShiftTimeout])
                % Grids.GridSize;
        return Grids.GridShift[(int) shift];
    }

    private void updatePattern() {
        if (mBmp == null) {
            return;
        }
        int shift = getShift();
        int shiftX = shift % Grids.GridSideSize;
        int shiftY = shift / Grids.GridSideSize;
        for (int i = 0; i < Grids.GridSize; i++) {
            int x = (i + shiftX) % Grids.GridSideSize;
            int y = ((i / Grids.GridSideSize) + shiftY) % Grids.GridSideSize;
            int color = (Grids.Patterns[mPattern][i] == 0) ? Color.TRANSPARENT : Color.BLACK;
            mBmp.setPixel(x, y, color);
        }
    }

    private void updateSettings() {
        int pattern = Settings.System.getIntForUser(
                getContentResolver(), Settings.System.SMART_PIXELS_PATTERN,
                5, UserHandle.USER_CURRENT);
        int shiftTimeout = Settings.System.getIntForUser(
                getContentResolver(), Settings.System.SMART_PIXELS_SHIFT_TIMEOUT,
                4, UserHandle.USER_CURRENT);
        mPattern = Math.max(0, Math.min(pattern, Grids.Patterns.length - 1));
        mShiftTimeout = Math.max(0, Math.min(shiftTimeout, Grids.ShiftTimeouts.length - 1));
    }
}
