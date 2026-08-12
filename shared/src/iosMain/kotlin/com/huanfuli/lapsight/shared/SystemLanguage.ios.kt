package com.huanfuli.lapsight.shared

import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages

actual fun systemLanguageTag(): String =
    NSLocale.preferredLanguages.firstOrNull()?.toString() ?: "en"
