package io.quietbuzz.app.util

import android.content.pm.ApplicationInfo

fun ApplicationInfo.isSystemApp(): Boolean = (flags and ApplicationInfo.FLAG_SYSTEM) != 0
