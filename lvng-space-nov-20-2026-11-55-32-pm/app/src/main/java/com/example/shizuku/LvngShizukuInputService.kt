package com.example.shizuku

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import androidx.annotation.Keep
import java.io.BufferedWriter
import java.io.OutputStreamWriter

/**
 * Privileged UserService executed inside Shizuku's shell/root process.
 * Injects touch taps directly via InputManager (with persistent shell `input` fallback)
 * so LVNG SPACE Macro works without AccessibilityService.
 */
@Keep
class LvngShizukuInputService : Binder() {

    private val lock = Any()
    private var shellProcess: Process? = null
    private var shellWriter: BufferedWriter? = null

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        return when (code) {
            TRANSACTION_TAP -> {
                data.enforceInterface(DESCRIPTOR)
                val x = data.readFloat()
                val y = data.readFloat()
                val durationMs = data.readLong()
                val ok = performTap(x, y, durationMs)
                reply?.writeNoException()
                reply?.writeInt(if (ok) 1 else 0)
                true
            }
            TRANSACTION_PING -> {
                data.enforceInterface(DESCRIPTOR)
                reply?.writeNoException()
                reply?.writeInt(1)
                true
            }
            TRANSACTION_DESTROY -> {
                data.enforceInterface(DESCRIPTOR)
                cleanup()
                reply?.writeNoException()
                true
            }
            else -> super.onTransact(code, data, reply, flags)
        }
    }

    private fun performTap(x: Float, y: Float, durationMs: Long): Boolean {
        val safeX = x.coerceAtLeast(1f)
        val safeY = y.coerceAtLeast(1f)
        val clampedDuration = durationMs.coerceIn(10L, 1000L)

        if (injectViaInputManager(safeX, safeY, clampedDuration)) {
            return true
        }
        return execShellTap(safeX.toInt(), safeY.toInt(), clampedDuration)
    }

    private fun injectViaInputManager(x: Float, y: Float, durationMs: Long): Boolean {
        return runCatching {
            val (instance, injectMethod) = resolveInputManager() ?: return@runCatching false
            val downTime = SystemClock.uptimeMillis()
            val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            val downOk = try {
                injectMethod.invoke(instance, downEvent, INJECT_INPUT_EVENT_MODE_ASYNC) as? Boolean ?: false
            } finally {
                downEvent.recycle()
            }
            if (!downOk) return@runCatching false

            if (durationMs > 20L) {
                Thread.sleep(durationMs.coerceAtMost(400L))
            }

            val upTime = SystemClock.uptimeMillis()
            val upEvent = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            try {
                injectMethod.invoke(instance, upEvent, INJECT_INPUT_EVENT_MODE_ASYNC) as? Boolean ?: false
            } finally {
                upEvent.recycle()
            }
        }.getOrDefault(false)
    }

    private fun resolveInputManager(): Pair<Any, java.lang.reflect.Method>? {
        val candidateClasses = listOf(
            "android.hardware.input.InputManagerGlobal",
            "android.hardware.input.InputManager"
        )
        for (clsName in candidateClasses) {
            val pair = runCatching {
                val cls = Class.forName(clsName)
                val getInstance = cls.getMethod("getInstance")
                val instance = getInstance.invoke(null) ?: return@runCatching null
                val inject = cls.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                instance to inject
            }.getOrNull()
            if (pair != null) return pair
        }
        return null
    }

    private fun execShellTap(x: Int, y: Int, durationMs: Long): Boolean {
        synchronized(lock) {
            return runCatching {
                val proc = shellProcess
                if (proc == null || !proc.isAlive) {
                    shellProcess = ProcessBuilder("sh").redirectErrorStream(true).start()
                    shellWriter = BufferedWriter(OutputStreamWriter(shellProcess!!.outputStream))
                }
                val cmd = if (durationMs >= 120L) {
                    "input swipe $x $y $x $y $durationMs\n"
                } else {
                    "input tap $x $y\n"
                }
                shellWriter!!.write(cmd)
                shellWriter!!.flush()
                true
            }.getOrElse {
                cleanup()
                false
            }
        }
    }

    private fun cleanup() {
        synchronized(lock) {
            runCatching { shellWriter?.close() }
            runCatching { shellProcess?.destroy() }
            shellWriter = null
            shellProcess = null
        }
    }

    companion object {
        const val DESCRIPTOR = "com.example.shizuku.ILvngInputService"
        const val TRANSACTION_TAP = IBinder.FIRST_CALL_TRANSACTION + 1
        const val TRANSACTION_PING = IBinder.FIRST_CALL_TRANSACTION + 2
        const val TRANSACTION_DESTROY = IBinder.FIRST_CALL_TRANSACTION + 3
        private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
    }
}
