/**
 * Modified MIT License
 *
 * Copyright 2016 OneSignal
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * 1. The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * 2. All copies of substantial portions of the Software may only be used in connection
 * with services provided by OneSignal.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.onesignal;

import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.onesignal.AndroidSupportV4Compat.ContextCompat;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationServices;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;

class LocationGMS {
   
   static class LocationPoint {
      Double lat;
      Double log;
      Float accuracy;
      Integer type;
      Boolean bg;
      Long timeStamp;
   }
   
   private static final int TIME_FOREGROUND = 5 * 60;
   private static final int TIME_BACKGROUND = 10 * 60;
   
   private static GoogleApiClientCompatProxy mGoogleApiClient;
   static String requestPermission;
   private static Context classContext;

   interface LocationHandler {
      void complete(LocationPoint point);
   }

   private static LocationHandler locationHandler;

   private static Thread fallbackFailThread;

   private static boolean locationCoarse;
   
   static void scheduleUpdate(Context context) {
      if (!hasLocationPermission(context) && OneSignal.shareLocation)
         return;
      
      long lastTime = getLastLocationTime(context);
      long minTime = 1000 * (OneSignal.isForeground() ? TIME_FOREGROUND : TIME_BACKGROUND);
      long scheduleTime = lastTime + minTime;
      
      SyncService.scheduleServiceSyncTask(context, scheduleTime);
   }
   
   private static void setLastLocationTime(long time) {
      final SharedPreferences prefs = OneSignal.getGcmPreferences(classContext);
      SharedPreferences.Editor editor = prefs.edit();
      editor.putLong("OS_LAST_LOCATION_TIME", time);
      editor.apply();
   }
   
   private static long getLastLocationTime(Context context) {
      final SharedPreferences prefs = OneSignal.getGcmPreferences(context);
      return prefs.getLong("OS_LAST_LOCATION_TIME", -TIME_BACKGROUND * 1000);
   }
   
   private static boolean hasLocationPermission(Context context) {
      return ContextCompat.checkSelfPermission(context, "android.permission.ACCESS_FINE_LOCATION") == PackageManager.PERMISSION_GRANTED
          || ContextCompat.checkSelfPermission(context, "android.permission.ACCESS_COARSE_LOCATION") == PackageManager.PERMISSION_GRANTED;
   }

   static void getLocation(Context context, boolean promptLocation, LocationHandler handler) {
      classContext = context;
      locationHandler = handler;
   
      if (!OneSignal.shareLocation) {
         fireFailedComplete();
         return;
      }
      
      int locationCoarsePermission = PackageManager.PERMISSION_DENIED;

      int locationFinePermission = ContextCompat.checkSelfPermission(context, "android.permission.ACCESS_FINE_LOCATION");
      if (locationFinePermission == PackageManager.PERMISSION_DENIED) {
         locationCoarsePermission = ContextCompat.checkSelfPermission(context, "android.permission.ACCESS_COARSE_LOCATION");
         locationCoarse = true;
      }

      if (android.os.Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
         if (locationFinePermission != PackageManager.PERMISSION_GRANTED && locationCoarsePermission != PackageManager.PERMISSION_GRANTED) {
            handler.complete(null);
            return;
         }

         startGetLocation();
      }
      else { // Android 6.0+
         if (locationFinePermission != PackageManager.PERMISSION_GRANTED) {
            try {
               PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
               List<String> permissionList = Arrays.asList(packageInfo.requestedPermissions);
               if (permissionList.contains("android.permission.ACCESS_FINE_LOCATION"))
                  requestPermission = "android.permission.ACCESS_FINE_LOCATION";
               else if (permissionList.contains("android.permission.ACCESS_COARSE_LOCATION")) {
                  if (locationCoarsePermission != PackageManager.PERMISSION_GRANTED)
                     requestPermission = "android.permission.ACCESS_COARSE_LOCATION";
               }

               if (requestPermission != null && promptLocation)
                  PermissionsActivity.startPrompt();
               else if (locationCoarsePermission == PackageManager.PERMISSION_GRANTED)
                  startGetLocation();
               else
                  fireFailedComplete();
            } catch (Throwable t) {
               t.printStackTrace();
            }
         }
         else
            startGetLocation();
      }
   }

   // Started from this class or PermissionActivity
   static void startGetLocation() {
      // Prevents overlapping requests
      if (fallbackFailThread != null)
         return;

      try {
         startFallBackThread();

         GoogleApiClientListener googleApiClientListener = new GoogleApiClientListener();
         GoogleApiClient googleApiClient = new GoogleApiClient.Builder(classContext)
             .addApi(LocationServices.API)
             .addConnectionCallbacks(googleApiClientListener)
             .addOnConnectionFailedListener(googleApiClientListener)
             .build();
         mGoogleApiClient = new GoogleApiClientCompatProxy(googleApiClient);

         mGoogleApiClient.connect();
      } catch (Throwable t) {
         OneSignal.Log(OneSignal.LOG_LEVEL.WARN, "Location permission exists but there was an error initializing: ", t);
         fireFailedComplete();
      }
   }

   private static void startFallBackThread() {
      fallbackFailThread = new Thread(new Runnable() {
         public void run() {
            try {
               Thread.sleep(30000);
               OneSignal.Log(OneSignal.LOG_LEVEL.WARN, "Location permission exists but GoogleApiClient timed out. Maybe related to mismatch google-play aar versions.");
               fireFailedComplete();
            } catch (Throwable t) {}
         }
      }, "OS_GMS_LOCATION_FALLBACK");
      fallbackFailThread.start();
   }

   static void fireFailedComplete() {
      PermissionsActivity.answered = false;

      fireComplete(null);
      if (mGoogleApiClient != null)
         mGoogleApiClient.disconnect();
   }
   
   private static void fireComplete(LocationPoint point) {
      locationHandler.complete(point);
      if (fallbackFailThread != null && !Thread.currentThread().equals(fallbackFailThread))
         fallbackFailThread.interrupt();
      fallbackFailThread = null;
   }
   
   private static void receivedLocationPoint(Location location) {
      LocationPoint point = new LocationPoint();
      
      point.accuracy = location.getAccuracy();
      point.bg = !OneSignal.isForeground();
      point.type = locationCoarse ? 0 : 1;
      point.timeStamp = location.getTime();
      
      // Coarse always gives out 14 digits and has an accuracy 2000.
      // Always rounding to 7 as this is what fine returns.
      if (locationCoarse) {
         point.lat = new BigDecimal(location.getLatitude()).setScale(7, RoundingMode.HALF_UP).doubleValue();
         point.log = new BigDecimal(location.getLongitude()).setScale(7, RoundingMode.HALF_UP).doubleValue();
      }
      else {
         point.lat = location.getLatitude();
         point.log = location.getLongitude();
      }
      
      fireComplete(point);
      setLastLocationTime(System.currentTimeMillis());
      scheduleUpdate(classContext);
   }
   
   private static LocationUpdateListener locationUpdateListener;
   
   private static class GoogleApiClientListener implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
      @Override
      public void onConnected(Bundle bundle) {
         PermissionsActivity.answered = false;
         Location location = FusedLocationApiWrapper.getLastLocation(mGoogleApiClient.realInstance());
         
         if (location != null) {
            receivedLocationPoint(location);
            mGoogleApiClient.disconnect();
         }
         else // Current point is null, setup a listener. Some devices need to this.
            locationUpdateListener = new LocationUpdateListener(mGoogleApiClient.realInstance());
      }

      @Override
      public void onConnectionSuspended(int i) {
         fireFailedComplete();
      }

      @Override
      public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
         fireFailedComplete();
      }
   }
   
   
   private static class LocationUpdateListener implements LocationListener {
      
      private GoogleApiClient mGoogleApiClient;
      
      LocationUpdateListener(GoogleApiClient googleApiClient) {
         mGoogleApiClient = googleApiClient;
   
         FusedLocationApiWrapper.removeLocationUpdates(mGoogleApiClient, this);
   
         LocationRequest locationRequest = new LocationRequest();
         locationRequest.setMaxWaitTime(20000);
         locationRequest.setPriority(LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY);
   
         FusedLocationApiWrapper.requestLocationUpdates(mGoogleApiClient, locationRequest, this);
      }
      
      @Override
      public void onLocationChanged(Location location) {
         receivedLocationPoint(location);
         
         if (mGoogleApiClient.isConnected()) {
            FusedLocationApiWrapper.removeLocationUpdates(mGoogleApiClient, this);
            mGoogleApiClient.disconnect();
         }
      }
   }
   
   static class FusedLocationApiWrapper {
      @SuppressWarnings("MissingPermission")
      static PendingResult<Status> requestLocationUpdates(GoogleApiClient googleApiClient, LocationRequest locationRequest, LocationListener locationListener) {
         return LocationServices.FusedLocationApi.requestLocationUpdates(googleApiClient, locationRequest, locationListener);
      }
   
      static PendingResult<Status> removeLocationUpdates(GoogleApiClient googleApiClient, LocationListener locationListener) {
         return LocationServices.FusedLocationApi.removeLocationUpdates(googleApiClient, locationListener);
      }
   
      @SuppressWarnings("MissingPermission")
      static Location getLastLocation(GoogleApiClient googleApiClient) {
         return LocationServices.FusedLocationApi.getLastLocation(googleApiClient);
      }
   }
}