package com.huanfuli.lapsight.shared

import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone

internal actual fun localUtcOffsetMinutesAt(epochMillis: Long): Int {
    val date = NSDate.dateWithTimeIntervalSince1970(epochMillis.toDouble() / 1000.0)
    return (NSTimeZone.localTimeZone.secondsFromGMTForDate(date) / 60).toInt()
}
