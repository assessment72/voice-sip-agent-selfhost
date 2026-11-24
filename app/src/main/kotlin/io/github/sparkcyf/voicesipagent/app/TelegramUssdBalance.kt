package io.github.sparkcyf.voicesipagent.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager

/** Executes a carrier USSD query and returns its network response (not an SMS). */
internal class TelegramUssdBalance(context: Context) {
    private val app = context.applicationContext

    fun query(callback: (Result<String>) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            callback(Result.failure(IllegalStateException("USSD callback requires Android 8 or later")))
            return
        }
        if (app.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            callback(Result.failure(SecurityException("CALL_PHONE permission not granted")))
            return
        }
        val telephony = app.getSystemService(TelephonyManager::class.java)
        try {
            telephony.sendUssdRequest("*144#", object : TelephonyManager.UssdResponseCallback() {
                override fun onReceiveUssdResponse(
                    telephonyManager: TelephonyManager,
                    request: String,
                    response: CharSequence,
                ) {
                    callback(Result.success(response.toString().take(1200)))
                }

                override fun onReceiveUssdResponseFailed(
                    telephonyManager: TelephonyManager,
                    request: String,
                    failureCode: Int,
                ) {
                    callback(Result.failure(IllegalStateException("USSD request failed ($failureCode)")))
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }
}
