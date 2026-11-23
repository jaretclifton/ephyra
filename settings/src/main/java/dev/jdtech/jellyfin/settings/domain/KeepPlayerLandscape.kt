package dev.jdtech.jellyfin.settings.domain

import android.content.Context

/**
 * CE (#363): where "Keep the player in landscape" ([AppPreferences.playerKeepLandscape]) works.
 *
 * The Android version does not decide whether the system honours the player's landscape request on
 * a wide window. A per-display setting the app cannot read does (ignore-orientation-request, shown
 * by `adb shell wm get-ignore-orientation-request`).
 *
 * Emulator check, 2026-11-23 (#917):
 * - Android 16 (API 36) and Android 14 (API 34) tablets with the display ignoring requests: the
 *   request is overridden (the player stays upright) or answered with a letterboxed box, which the
 *   player detects and then follows the device.
 * - The same tablets with the display honouring requests: the player turns to landscape.
 */
object KeepPlayerLandscape {
    /**
     * Whether the settings show the switch: the device can give the player a wide window, now (a
     * tablet, an unfolded foldable) or after unfolding (a hinge-angle sensor, so a folded foldable
     * shows it too; a flip phone, narrow either way, shows it as well). Phones always play in
     * landscape, so the switch would do nothing there.
     */
    fun isShown(context: Context): Boolean = WideWindow.isPossible(context)
}
