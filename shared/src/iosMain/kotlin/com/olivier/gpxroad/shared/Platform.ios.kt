package com.olivier.gpxroad.shared

import platform.UIKit.UIDevice

internal actual fun platformName(): String = "iOS ${UIDevice.currentDevice.systemVersion}"
