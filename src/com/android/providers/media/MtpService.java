/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.providers.media;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbManager;
import android.mtp.MtpDatabase;
import android.mtp.MtpServer;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.UserHandle;
import android.os.storage.StorageEventListener;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.io.File;
import java.util.HashMap;

/**
 * The singleton service backing instances of MtpServer that are started for the foreground user.
 * The service has the responsibility of retrieving user storage information and managing server
 * lifetime.
 */
public class MtpService extends Service {
    private static final String TAG = "MtpService";
    private static final boolean LOGD = false;

    // We restrict PTP to these subdirectories
    private static final String[] PTP_DIRECTORIES = new String[] {
        Environment.DIRECTORY_DCIM,
        Environment.DIRECTORY_PICTURES,
    };

    private final StorageEventListener mStorageEventListener = new StorageEventListener() {
        @Override
        public synchronized void onStorageStateChanged(String path, String oldState,
                String newState) {
            Log.d(TAG, "onStorageStateChanged " + path + " " + oldState + " -> " + newState);
            if (Environment.MEDIA_MOUNTED.equals(newState)) {
                for (int i = 0; i < mVolumes.length; i++) {
                    StorageVolume volume = mVolumes[i];
                    if (volume.getPath().equals(path)) {
                        mVolumeMap.put(path, volume);
                        if (mUnlocked && (volume.isPrimary() || !mPtpMode)) {
                            addStorage(volume);
                        }
                        break;
                    }
                }
            } else if (Environment.MEDIA_MOUNTED.equals(oldState)) {
                if (mVolumeMap.containsKey(path)) {
                    removeStorage(mVolumeMap.remove(path));
                }
            }
        }
    };

    @GuardedBy("this")
    private ServerHolder sServerHolder;

    private StorageManager mStorageManager;

    @GuardedBy("this")
    private boolean mUnlocked;
    @GuardedBy("this")
    private boolean mPtpMode;

    // A map of user volumes that are currently mounted.
    @GuardedBy("this")
    private HashMap<String, StorageVolume> mVolumeMap;

    // All user volumes in existence, in any state.
    @GuardedBy("this")
    private StorageVolume[] mVolumes;

    @Override
    public void onCreate() {
        mStorageManager = this.getSystemService(StorageManager.class);
        mStorageManager.registerListener(mStorageEventListener);
    }

    @Override
    public void onDestroy() {
        mStorageManager.unregisterListener(mStorageEventListener);
        synchronized (MtpService.class) {
            if (sServerHolder != null) {
                sServerHolder.database.setServer(null);
            }
        }
    }

    @Override
    public synchronized int onStartCommand(Intent intent, int flags, int startId) {
        UserHandle user = new UserHandle(ActivityManager.getCurrentUser());
        mUnlocked = intent.getBooleanExtra(UsbManager.USB_DATA_UNLOCKED, false);
        mPtpMode = intent.getBooleanExtra(UsbManager.USB_FUNCTION_PTP, false);
        mVolumes = StorageManager.getVolumeList(user.getIdentifier(), 0);
        mVolumeMap = new HashMap<>();
        for (StorageVolume v : mVolumes) {
            if (v.getState().equals(Environment.MEDIA_MOUNTED)) {
                mVolumeMap.put(v.getPath(), v);
            }
        }
        String[] subdirs = null;
        if (mPtpMode) {
            Environment.UserEnvironment env = new Environment.UserEnvironment(user.getIdentifier());
            int count = PTP_DIRECTORIES.length;
            subdirs = new String[count];
            for (int i = 0; i < count; i++) {
                File file = env.buildExternalStoragePublicDirs(PTP_DIRECTORIES[i])[0];
                // make sure this directory exists
                file.mkdirs();
                subdirs[i] = file.getName();
            }
        }
        final StorageVolume primary = StorageManager.getPrimaryVolume(mVolumes);
        try {
            startServer(primary, subdirs, user);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Couldn't find the current user!: " + e.getMessage());
        }
        return START_REDELIVER_INTENT;
    }

    /**
     * Manage MtpServer, creating only when running as the current user.
     */
    private synchronized void startServer(StorageVolume primary, String[] subdirs, UserHandle user)
            throws PackageManager.NameNotFoundException {
        if (sServerHolder != null) {
            if (LOGD) {
                Log.d(TAG, "Cannot launch second MTP server.");
            }
            // Previously executed MtpServer is still running. It will be terminated
            // because MTP device FD will become invalid soon. Also MtpService will get new
            // intent after that when UsbDeviceManager configures USB with new state.
            return;
        }

        Log.d(TAG, "starting MTP server in " + (mPtpMode ? "PTP mode" : "MTP mode") +
                " with storage " + primary.getPath() + (mUnlocked ? " unlocked" : ""));
        final MtpDatabase database = new MtpDatabase(this,
                createPackageContextAsUser(this.getPackageName(), 0, user),
                MediaProvider.EXTERNAL_VOLUME, subdirs);
        String deviceSerialNumber = Build.getSerial();
        if (Build.UNKNOWN.equals(deviceSerialNumber)) {
            deviceSerialNumber = "????????";
        }
        final MtpServer server =
                new MtpServer(database, mPtpMode, new OnServerTerminated(), Build.MANUFACTURER,
                        Build.MODEL, "1.0", deviceSerialNumber);
        database.setServer(server);
        sServerHolder = new ServerHolder(server, database);

        // Add currently mounted and enabled storages to the server
        if (mUnlocked) {
            if (mPtpMode) {
                addStorage(primary);
            } else {
                for (StorageVolume v : mVolumeMap.values()) {
                    addStorage(v);
                }
            }
        }
        server.start();
    }

    private final IMtpService.Stub mBinder =
            new IMtpService.Stub() {
    };

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }


    private void addStorage(StorageVolume volume) {
        Log.v(TAG, "Adding MTP storage:" + volume.getPath());
        synchronized (this) {
            if (sServerHolder != null) {
                sServerHolder.database.addStorage(volume);
            }
        }
    }

    private void removeStorage(StorageVolume volume) {
        synchronized (MtpService.class) {
            if (sServerHolder != null) {
                sServerHolder.database.removeStorage(volume);
            }
        }
    }

    private static class ServerHolder {
        @NonNull final MtpServer server;
        @NonNull final MtpDatabase database;

        ServerHolder(@NonNull MtpServer server, @NonNull MtpDatabase database) {
            Preconditions.checkNotNull(server);
            Preconditions.checkNotNull(database);
            this.server = server;
            this.database = database;
        }

        void close() {
            this.database.setServer(null);
        }
    }

    private class OnServerTerminated implements Runnable {
        @Override
        public void run() {
            synchronized (MtpService.class) {
                if (sServerHolder == null) {
                    Log.e(TAG, "sServerHolder is unexpectedly null.");
                    return;
                }
                sServerHolder.close();
                sServerHolder = null;
            }
        }
    }
}
