package dev.jarvis.app

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import dev.jarvis.core.CommandRouter
import dev.jarvis.core.LocalCommand
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ToolReply(val text: String, val needsCamera: Boolean = false)

class LocalTools(private val context: Context) {
    fun execute(command: LocalCommand, language: JarvisLanguage = JarvisLanguage.ENGLISH): ToolReply {
        fun reply(english: String, malayalam: String, needsCamera: Boolean = false) =
            ToolReply(if (language == JarvisLanguage.MALAYALAM) malayalam else english, needsCamera)
        if (context.getSystemService(KeyguardManager::class.java).isDeviceLocked) {
            return reply("Unlock your phone first, then ask me again.", "ഫോൺ അൺലോക്ക് ചെയ്ത ശേഷം വീണ്ടും ചോദിക്കൂ.")
        }
        return try {
            when (command) {
                is LocalCommand.Torch -> torch(command.on, language)
                is LocalCommand.Volume -> {
                    context.getSystemService(AudioManager::class.java).adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        if (command.change > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_SHOW_UI,
                    )
                    if (command.change > 0) reply("Media volume up.", "മീഡിയ ശബ്ദം കൂട്ടി.")
                    else reply("Media volume down.", "മീഡിയ ശബ്ദം കുറച്ചു.")
                }
                is LocalCommand.Timer -> {
                    launch(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, command.seconds)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis timer").putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                    val parts = buildList {
                        val hours = command.seconds / 3600
                        val minutes = command.seconds % 3600 / 60
                        val seconds = command.seconds % 60
                        if (hours > 0) add("$hours ${if (hours == 1) "hour" else "hours"}")
                        if (minutes > 0) add("$minutes ${if (minutes == 1) "minute" else "minutes"}")
                        if (seconds > 0) add("$seconds ${if (seconds == 1) "second" else "seconds"}")
                    }
                    reply("Starting a timer for ${parts.joinToString(" and ")}.", "${command.seconds} സെക്കൻഡിന്റെ ടൈമർ ആരംഭിക്കുന്നു.")
                }
                is LocalCommand.Alarm -> {
                    require(command.hour in 0..23 && command.minute in 0..59)
                    launch(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, command.hour)
                        .putExtra(AlarmClock.EXTRA_MINUTES, command.minute).putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis alarm")
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, false))
                    val time = LocalTime.of(command.hour, command.minute).format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
                    reply("Opening Clock for your $time alarm. Review and save it there.", "$time-ന് അലാറം സജ്ജമാക്കാൻ ക്ലോക്ക് തുറക്കുന്നു. അവിടെ പരിശോധിച്ച് സേവ് ചെയ്യൂ.")
                }
                is LocalCommand.OpenApp -> openApp(command.name, language)
                LocalCommand.Battery -> {
                    val (level, plugged) = readBattery(context)
                    if (level !in 0..100) return reply("I couldn't read the battery level.", "ബാറ്ററി നില അറിയാൻ കഴിഞ്ഞില്ല.")
                    val advice = batteryAdvice(level, plugged)
                    reply("Your battery is at $level percent.${advice.first}", "ബാറ്ററി $level ശതമാനമാണ്.${advice.second}")
                }
                LocalCommand.Time -> {
                    val time = LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
                    reply("It's $time.", "ഇപ്പോൾ സമയം $time ആണ്.")
                }
                LocalCommand.Settings -> { launch(Intent(Settings.ACTION_SETTINGS)); reply("Opening Settings.", "ക്രമീകരണങ്ങൾ തുറക്കുന്നു.") }
                LocalCommand.Camera -> { launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)); reply("Opening Camera.", "ക്യാമറ തുറക്കുന്നു.") }
                LocalCommand.Help, LocalCommand.Unknown -> reply("Ask a question, set an alarm or timer, open an app, or check the battery.", "ചോദ്യം ചോദിക്കാം, അലാറമോ ടൈമറോ സജ്ജമാക്കാം, ആപ്പ് തുറക്കാം, ബാറ്ററി നില പരിശോധിക്കാം.")
                LocalCommand.Blocked -> reply("Use your phone directly for messages, calls, deletions, and payments.", "സന്ദേശങ്ങൾ, വിളികൾ, നീക്കംചെയ്യൽ, പണമിടപാടുകൾ എന്നിവയ്ക്ക് ഫോൺ നേരിട്ട് ഉപയോഗിക്കൂ.")
            }
        } catch (_: SecurityException) { reply("Android hasn't allowed that action. Check its permission in Settings.", "ഈ പ്രവർത്തനത്തിന് Android അനുമതി നൽകിയിട്ടില്ല. ക്രമീകരണങ്ങളിൽ അനുമതി പരിശോധിക്കൂ.") }
        catch (_: Exception) { reply("That action isn't available on this phone.", "ഈ പ്രവർത്തനം ഈ ഫോണിൽ ലഭ്യമല്ല.") }
    }

    private fun torch(on: Boolean, language: JarvisLanguage): ToolReply {
        val ml = language == JarvisLanguage.MALAYALAM
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return ToolReply(if (ml) "ടോർച്ച് നിയന്ത്രിക്കാൻ ക്യാമറ അനുമതി നൽകൂ." else "Allow camera access once so I can control the flashlight.", needsCamera = true)
        }
        val camera = context.getSystemService(CameraManager::class.java)
        val id = camera.cameraIdList.firstOrNull {
            camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return ToolReply(if (ml) "ഈ ഫോണിൽ ടോർച്ച് ഇല്ല." else "This device doesn't have a flashlight.")
        camera.setTorchMode(id, on)
        return ToolReply(if (ml) { if (on) "ടോർച്ച് ഓണാക്കി." else "ടോർച്ച് ഓഫ് ചെയ്തു." }
            else if (on) "Flashlight on." else "Flashlight off.")
    }

    @Suppress("DEPRECATION")
    private fun openApp(request: String, language: JarvisLanguage): ToolReply {
        val ml = language == JarvisLanguage.MALAYALAM
        val aliases = mapOf("വാട്സ്ആപ്പ്" to "whatsapp", "യൂട്യൂബ്" to "youtube", "ക്രോം" to "chrome", "മാപ്സ്" to "maps")
        val name = aliases[CommandRouter.normalize(request)] ?: CommandRouter.normalize(request)
        val apps = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val matches = apps.filter { CommandRouter.normalize(it.loadLabel(context.packageManager).toString()) == name }
        if (matches.size != 1) return ToolReply(if (ml) "$request എന്ന ആപ്പ് കണ്ടെത്താനായില്ല. ആപ്പിന്റെ കൃത്യമായ പേര് പറയൂ."
            else "I couldn't find one installed app named $request. Use its exact name.")
        val target = matches.single()
        val packageName = target.activityInfo.packageName
        val banking = listOf("bank", "paypal", "gpay", "google.android.apps.nbu.paisa", "paytm", "phonepe", "yono", "bhim", "wallet")
        if (banking.any { packageName.lowercase(Locale.ROOT).contains(it) || name.contains(it) }) {
            return ToolReply(if (ml) "സാമ്പത്തിക ആപ്പുകൾ നേരിട്ട് തുറക്കൂ." else "Open financial apps directly.")
        }
        launch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(packageName, target.activityInfo.name))
        return ToolReply(if (ml) "ആപ്പ് തുറക്കുന്നു." else "Opening ${target.loadLabel(context.packageManager)}.")
    }

    private fun launch(intent: Intent) { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

internal fun readBattery(context: Context): Pair<Int, Int?> {
    val level = context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    return level to status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
}

internal fun batteryAdvice(level: Int, plugged: Int?): Pair<String, String> =
    if (level in 0..15 && plugged == 0) " Plug it in soon." to " ഉടൻ ചാർജിൽ ഇടൂ." else "" to ""
