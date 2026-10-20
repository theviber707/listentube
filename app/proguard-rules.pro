# SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
#
# SPDX-License-Identifier: GPL-3.0-or-later

-keepattributes *Annotation*,InnerClasses,Signature
-dontwarn kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    @kotlinx.serialization.Serializable <fields>;
}
