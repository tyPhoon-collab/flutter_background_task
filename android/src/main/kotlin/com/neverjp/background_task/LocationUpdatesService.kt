/**
Copyright [2024] [Never Inc.]
Copyright [2019] [Ali Almoullim]

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.neverjp.background_task

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.neverjp.background_task.lib.ChannelName
import com.neverjp.background_task.lib.StatusEventStreamHandler
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.embedding.engine.loader.FlutterLoader
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.FlutterCallbackInformation

data class LocationData(
    val lat: Double?,
    val lng: Double?,
    val speed: Float?,
    val time: Double?
)

class LocationUpdatesService: Service() {

    private val binder = LocalBinder()
    private var notificationManager: NotificationManager? = null
    private var locationRequest: LocationRequest? = null
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var fusedLocationCallback: LocationCallback? = null
    private var isGoogleApiAvailable: Boolean = false
    private var serviceHandler: Handler? = null
    private var methodChannel: MethodChannel? = null

    private val pref: SharedPreferences
        get() = applicationContext.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)

    enum class DesiredAccuracy(val accuracy: String) {
        PRIORITY_HIGH_ACCURACY("priorityHighAccuracy"),
        PRIORITY_BALANCED_POWER_ACCURACY("priorityBalancedPowerAccuracy"),
        PRIORITY_LOW_POWER("priorityLowPower");

        companion object {
            fun lookup(value: String): DesiredAccuracy {
                return values().find { it.accuracy == value } ?: throw IllegalArgumentException()
            }
        }

        fun getLocationPriority(): Int {
            return when (this) {
                PRIORITY_HIGH_ACCURACY -> Priority.PRIORITY_HIGH_ACCURACY
                PRIORITY_BALANCED_POWER_ACCURACY -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
                PRIORITY_LOW_POWER -> Priority.PRIORITY_LOW_POWER
            }
        }
    }

    companion object {
        private val TAG = LocationUpdatesService::class.java.simpleName
        var isRunning: Boolean = false
            private set

        private val _locationLiveData = MutableLiveData<LocationData>()
        val locationLiveData: LiveData<LocationData> = _locationLiveData

        val statusLiveData = MutableLiveData<String>()

        var NOTIFICATION_TITLE = "Background task is running"
        var NOTIFICATION_MESSAGE = "Background task is running"
        var NOTIFICATION_ICON = "@mipmap/ic_launcher"
        private const val PACKAGE_NAME =
            "com.google.android.gms.location.sample.locationupdatesforegroundservice"
        private const val CHANNEL_ID = "background_task_channel_01"
        private const val EXTRA_STARTED_FROM_NOTIFICATION = "$PACKAGE_NAME.started_from_notification"

        private const val NOTIFICATION_ID = 373737

        private lateinit var broadcastReceiver: BroadcastReceiver
        private const val STOP_SERVICE = "stop_service"

        const val isEnabledEvenIfKilledKey = "isEnabledEvenIfKilled"
        const val distanceFilterKey = "distanceFilter"
        const val desiredAccuracyKey = "androidDesiredAccuracy"
        const val updateIntervalInMillisecondsKey = "updateIntervalInMilliseconds"
        const val callbackDispatcherRawHandleKey = "callbackDispatcherRawHandle"
        const val callbackHandlerRawHandleKey = "callbackHandlerRawHandle"

        const val PREF_FILE_NAME = "BACKGROUND_TASK"
    }

    private val notification: NotificationCompat.Builder
        get() {
            val intent = Intent(this, getMainActivityClass(this))
            intent.putExtra(EXTRA_STARTED_FROM_NOTIFICATION, true)
            intent.action = "Localisation"
            val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            } else {
                PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val builder = NotificationCompat.Builder(this, "BackgroundTaskLocation")
                .setContentTitle(NOTIFICATION_TITLE)
                .setOngoing(true)
                .setSound(null)
                .setVibrate(null)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSmallIcon(resources.getIdentifier(NOTIFICATION_ICON, "mipmap", packageName))
                .setWhen(System.currentTimeMillis())
                .setContentIntent(pendingIntent)
                .setContentText(NOTIFICATION_MESSAGE)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setChannelId(CHANNEL_ID)

            }
            return builder
        }

    inner class LocalBinder : Binder() {
        internal val service: LocationUpdatesService
            get() = this@LocationUpdatesService
    }

    private fun createRequest(distanceFilter: Float, updateIntervalInMilliseconds: Long, desiredAccuracy: String): LocationRequest =
        LocationRequest.Builder(
            DesiredAccuracy.lookup(desiredAccuracy).getLocationPriority(),
            updateIntervalInMilliseconds
        ).apply {
            setMinUpdateDistanceMeters(distanceFilter)
            setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
            setWaitForAccurateLocation(true)
        }.build()

    override fun onCreate() {
        val googleAPIAvailability = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(applicationContext)
        isGoogleApiAvailable = googleAPIAvailability == ConnectionResult.SUCCESS
        Log.d(TAG,"isGoogleApiAvailable $isGoogleApiAvailable")
        if (isGoogleApiAvailable) {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            fusedLocationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    super.onLocationResult(locationResult)
                    val newLastLocation = locationResult.lastLocation
                    val lat = newLastLocation?.latitude
                    val lng = newLastLocation?.longitude
                    val speed = newLastLocation?.speed
                    val time = newLastLocation?.time?.toDouble()

                    val value = "lat:${lat ?: 0} lng:${lng ?: 0} speed:${speed ?: 0} time:${time ?: 0}"
                    _locationLiveData.value = LocationData(lat = lat, lng = lng, speed = speed, time = time)
                    statusLiveData.value = StatusEventStreamHandler.StatusType.Updated(value).value

                    pref.getLong(callbackHandlerRawHandleKey, 0).also {
                        if (it != 0.toLong()) {
                            val args = HashMap<String, Any?>()
                            args["callbackHandlerRawHandle"] = it
                            args["lat"] = lat ?: 0
                            args["lng"] = lng ?: 0
                            args["speed"] = speed ?: 0
                            args["time"] = time ?: 0
                            methodChannel?.invokeMethod("background_handler", args)
                        }
                    }
                }
            }
        }

        val handlerThread = HandlerThread(TAG)
        handlerThread.start()
        serviceHandler = Handler(handlerThread.looper)

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = NOTIFICATION_TITLE
            val mChannel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW)
            mChannel.setSound(null, null)
            mChannel.enableVibration(false)
            notificationManager!!.createNotificationChannel(mChannel)
        }

        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == STOP_SERVICE) {
                    removeLocationUpdates()
                }
            }
        }

        val filter = IntentFilter()
        filter.addAction(STOP_SERVICE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(broadcastReceiver, filter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(broadcastReceiver, filter)
        }

        pref.getLong(callbackDispatcherRawHandleKey, 0).also { callbackHandle ->
            Log.d(TAG, "onStartCommand callbackHandle: $callbackHandle")
            if (callbackHandle == 0.toLong()) {
                return@also
            }

            // ネイティブシステムを起動
            val flutterLoader = FlutterLoader().apply {
                startInitialization(applicationContext)
                ensureInitializationComplete(applicationContext, arrayOf())
            }
            // コールバック関数を取得
            val callbackInfo = FlutterCallbackInformation.lookupCallbackInformation(callbackHandle)
            val dartCallback = DartExecutor.DartCallback(
                applicationContext.assets,
                flutterLoader.findAppBundlePath(),
                callbackInfo
            )
            // コールバックを実行
            val engine = FlutterEngine(applicationContext).apply {
                dartExecutor.executeDartCallback(dartCallback)
            }

            // メソッドチャンネル設定
            methodChannel = MethodChannel(engine.dartExecutor, ChannelName.METHODS.value)
        }

        val distanceFilter = pref.getFloat(distanceFilterKey, 0.0.toFloat())
        val updateIntervalInMilliseconds = pref.getLong(updateIntervalInMillisecondsKey, 0.0.toLong())
        val desiredAccuracy = pref.getString(desiredAccuracyKey, "")
        val priority = DesiredAccuracy.lookup(desiredAccuracy.toString()).getLocationPriority()

        locationRequest = createRequest(distanceFilter, updateIntervalInMilliseconds, desiredAccuracy.toString())
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // https://github.com/JigarRangani/ForGroundLocation/blob/main/app/src/main/java/com/jigar/locationforground/LocationForegroundService.kt#L29
        if (!checkLocationPermissionIsGiven()) return START_NOT_STICKY
        updateNotification()
        requestLocationUpdates()

        isRunning = true
        statusLiveData.value = StatusEventStreamHandler.StatusType.Start.value
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        unregisterReceiver(broadcastReceiver)
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        try {
            if (isGoogleApiAvailable && locationRequest != null) {
                fusedLocationClient!!.requestLocationUpdates(
                    locationRequest!!,
                    fusedLocationCallback!!,
                    Looper.myLooper()
                )
            }
        } catch (unlikely: SecurityException) {
            Log.e(TAG, "$unlikely")
        }
    }

    // https://github.com/JigarRangani/ForGroundLocation/blob/main/app/src/main/java/com/jigar/locationforground/LocationForegroundService.kt
    private fun updateNotification() {
        if (!isRunning) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification.build(), FOREGROUND_SERVICE_TYPE_LOCATION)
            }else{
                startForeground(NOTIFICATION_ID, notification.build())
            }
        } else {
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification.build())
        }
    }

    private fun removeLocationUpdates() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun getMainActivityClass(context: Context): Class<*>? {
        val packageName = context.packageName
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        val className = launchIntent?.component?.className ?: return null
        return try {
            Class.forName(className)
        } catch (e: ClassNotFoundException) {
            e.printStackTrace()
            null
        }
    }

    private fun checkLocationPermissionIsGiven() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}