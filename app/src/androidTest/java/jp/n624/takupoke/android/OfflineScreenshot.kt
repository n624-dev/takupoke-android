package jp.n624.takupoke.android

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Same verified log transport as ScreenTest; synthetic data only, opt-in capture. */
internal fun offlineScreenshot(compose:ComposeContentTestRule,name:String) {
    if(InstrumentationRegistry.getArguments().getString("takupokeScreenshots")!="true")return
    compose.waitForIdle()
    val instrumentation=InstrumentationRegistry.getInstrumentation();instrumentation.waitForIdleSync()
    val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
    try {
        val output=ByteArrayOutputStream();check(bitmap.compress(Bitmap.CompressFormat.PNG,100,output))
        val bytes=output.toByteArray();check(bytes.size in 9..2*1024*1024)
        val filename="$name.png"
        fun emit(record:JSONObject) { Log.i("TakupokeScreenshots","TAKUPOKE_SCREENSHOT $record") }
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        emit(JSONObject().put("type","begin").put("name",filename).put("size",bytes.size).put("sha256",hash))
        val data=Base64.encodeToString(bytes,Base64.NO_WRAP)
        for(offset in data.indices step 2048)emit(JSONObject().put("type","chunk").put("name",filename).put("offset",offset).put("data",data.substring(offset,minOf(offset+2048,data.length))))
        emit(JSONObject().put("type","end").put("name",filename))
    } finally { bitmap.recycle() }
}
