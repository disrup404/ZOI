package com.chronodisrupt.zoi

import java.util.Locale

internal sealed class DeviceCommand {
    data object OpenContacts : DeviceCommand()
    data object DialContact : DeviceCommand()
    data class CreateCalendarEvent(val title: String) : DeviceCommand()
    data class SetAlarm(val hour: Int, val minute: Int) : DeviceCommand()
    data class SetTimer(val seconds: Int) : DeviceCommand()
    data object OpenFile : DeviceCommand()
}

/**
 * Only maps a small, explicit set of phrases to native Android intents.
 * The model cannot supply arbitrary intents, package names, or commands.
 */
internal fun parseDeviceCommand(message: String): DeviceCommand? {
    // Speech transcripts and normal typed messages often include terminal punctuation.\n    // Remove it before matching so commands like "Set an alarm for 6:30 AM." work.\n    val text = message.trim().lowercase(Locale.ROOT)\n        .replace(Regex("""[.!?,;:]+$"""), "")\n        .trim()

    if (Regex("""^(?:open|show|choose|pick) (?:my )?(?:contacts|contact list)$""").matches(text) ||
        Regex("""^(?:find|select|choose|pick) (?:a )?contact$""").matches(text)
    ) return DeviceCommand.OpenContacts

    if (Regex("""^(?:call|phone) (?:a )?contact$""").matches(text) ||
        Regex("""^(?:call|phone) someone from my contacts$""").matches(text)
    ) return DeviceCommand.DialContact

    val eventMatch = Regex("""^(?:add|create|schedule) (?:an? )?event(?: called| titled)?\s+(.+)$""").matchEntire(text)
    if (eventMatch != null) {
        val title = eventMatch.groupValues[1].trim().take(100)
        if (title.isNotBlank()) return DeviceCommand.CreateCalendarEvent(title)
    }

    val alarmMatch = Regex("""^(?:set|create) (?:an? )?alarm for (\d{1,2})(?::(\d{2}))?\s*(am|pm)?$""").matchEntire(text)
    if (alarmMatch != null) {
        var hour = alarmMatch.groupValues[1].toIntOrNull() ?: return null
        val minute = alarmMatch.groupValues[2].ifBlank { "0" }.toIntOrNull() ?: return null
        val meridiem = alarmMatch.groupValues[3]
        if (hour !in 0..23 || minute !in 0..59) return null
        if (meridiem.isNotBlank()) {
            if (hour !in 1..12) return null
            hour = when {
                meridiem == "am" && hour == 12 -> 0
                meridiem == "pm" && hour != 12 -> hour + 12
                else -> hour
            }
        }
        return DeviceCommand.SetAlarm(hour, minute)
    }

    val timerMatch = Regex("""^(?:set|start) (?:a )?timer for (\d{1,4})\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)$""").matchEntire(text)
    if (timerMatch != null) {
        val amount = timerMatch.groupValues[1].toIntOrNull() ?: return null
        val unit = timerMatch.groupValues[2]
        val multiplier = when {
            unit.startsWith("hour") || unit.startsWith("hr") -> 3600
            unit.startsWith("minute") || unit.startsWith("min") -> 60
            else -> 1
        }
        val seconds = amount.toLong() * multiplier
        if (seconds in 1..86400) return DeviceCommand.SetTimer(seconds.toInt())
    }

    if (Regex("""^(?:open|choose|select|pick) (?:a )?file$""").matches(text) ||
        text == "browse files" || text == "open my files"
    ) return DeviceCommand.OpenFile

    return null
}
