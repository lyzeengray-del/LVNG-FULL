package com.example.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

data class ShizukuLiveStatus(
    val isInstalled: Boolean = false,
    val isRunning: Boolean = false,
    val isPermissionGranted: Boolean = false,
    val isUserServiceBound: Boolean = false
) {
    val isReady: Boolean
        get() = isRunning && isPermissionGranted
}

/**
 * Manages live Shizuku binder detection, runtime permission prompts ("Allow LVNG SPACE"),
 * and privileged UserService binding for Macro auto-tap execution.
 */
object LvngShizukuManager {

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val SHIZUKU_PERMISSION_REQ_CODE = 10421

    private val _status = MutableStateFlow(ShizukuLiveStatus())
    val status: StateFlow<ShizukuLiveStatus> = _status.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var remoteInputBinder: IBinder? = null

    @Volatile
    private var isBindingInProgress = false

    @Volatile
    private var listenersRegistered = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        remoteInputBinder = null
        isBindingInProgress = false
        refreshStatus()
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQ_CODE) {
                refreshStatus()
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    bindUserServiceIfNeeded()
                }
            }
        }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remoteInputBinder = service
            isBindingInProgress = false
            _status.value = _status.value.copy(isUserServiceBound = service?.isBinderAlive == true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remoteInputBinder = null
            isBindingInProgress = false
            _status.value = _status.value.copy(isUserServiceBound = false)
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        if (!listenersRegistered) {
            listenersRegistered = true
            runCatching { Shizuku.addBinderReceivedListenerSticky(binderReceivedListener) }
            runCatching { Shizuku.addBinderDeadListener(binderDeadListener) }
            runCatching { Shizuku.addRequestPermissionResultListener(permissionResultListener) }
        }
        refreshStatus(context)
    }

    fun refreshStatus(context: Context? = appContext): ShizukuLiveStatus {
        val ctx = context?.applicationContext ?: appContext
        if (ctx != null) appContext = ctx

        val installed = ctx?.let { isPackageInstalled(it, SHIZUKU_PACKAGE) } ?: false
        val running = runCatching {
            Shizuku.pingBinder() && !Shizuku.isPreV11()
        }.getOrDefault(false)

        val granted = if (running) {
            runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
        } else {
            false
        }

        if (!running || !granted) {
            remoteInputBinder = null
            isBindingInProgress = false
        }

        val bound = remoteInputBinder?.isBinderAlive == true
        val snapshot = ShizukuLiveStatus(
            isInstalled = installed,
            isRunning = running,
            isPermissionGranted = granted,
            isUserServiceBound = bound
        )
        _status.value = snapshot

        if (snapshot.isReady && !bound) {
            bindUserServiceIfNeeded()
        }
        return snapshot
    }

    fun requestPermission(context: Context): Boolean {
        val current = refreshStatus(context)
        if (!current.isRunning) return false
        if (current.isPermissionGranted) {
            bindUserServiceIfNeeded()
            return true
        }
        return runCatching {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQ_CODE)
            true
        }.getOrDefault(false)
    }

    fun openShizukuApp(context: Context): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(launchIntent)
            true
        }.getOrDefault(false)
    }

    fun isReady(context: Context? = appContext): Boolean = refreshStatus(context).isReady

    fun bindUserServiceIfNeeded() {
        val ctx = appContext ?: return
        if (!refreshStatus(ctx).isReady) return
        if (remoteInputBinder?.isBinderAlive == true || isBindingInProgress) return

        val args = Shizuku.UserServiceArgs(
            ComponentName(ctx.packageName, LvngShizukuInputService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("lvng_input")
            .debuggable(BuildConfig.DEBUG)
            .version(1)

        isBindingInProgress = true
        runCatching {
            Shizuku.bindUserService(args, userServiceConnection)
        }.onFailure {
            isBindingInProgress = false
        }
    }

    suspend fun dispatchClickAt(xPx: Float, yPx: Float, durationMs: Long): Boolean {
        return withContext(Dispatchers.IO) {
            val current = refreshStatus()
            if (!current.isReady) return@withContext false

            val binder = remoteInputBinder?.takeIf { it.isBinderAlive }
            if (binder == null) {
                bindUserServiceIfNeeded()
                return@withContext false
            }

            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(LvngShizukuInputService.DESCRIPTOR)
                data.writeFloat(xPx)
                data.writeFloat(yPx)
                data.writeLong(durationMs)
                val transacted = binder.transact(
                    LvngShizukuInputService.TRANSACTION_TAP,
                    data,
                    reply,
                    0
                )
                if (!transacted) return@withContext false
                reply.readException()
                reply.readInt() == 1
            } catch (_: Throwable) {
                remoteInputBinder = null
                bindUserServiceIfNeeded()
                false
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        }.getOrDefault(false)
    }
}
