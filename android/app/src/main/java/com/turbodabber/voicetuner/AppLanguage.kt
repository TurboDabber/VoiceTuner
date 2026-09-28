package com.turbodabber.voicetuner

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object AppLanguage {
    val options = linkedMapOf("pl" to "Polski", "en" to "English", "de" to "Deutsch",
        "fr" to "Français", "ru" to "Русский", "zh" to "简体中文", "ja" to "日本語", "es" to "Español")

    fun current(context: Context): String {
        val tag = if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (locales.isEmpty) context.resources.configuration.locales[0].language else locales[0].language
        } else context.getSharedPreferences("language", Context.MODE_PRIVATE).getString("tag", null)
            ?: context.resources.configuration.locales[0].language
        return tag.takeIf { it in options } ?: "en"
    }

    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return context
        val tag = context.getSharedPreferences("language", Context.MODE_PRIVATE).getString("tag", null) ?: return context
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(tag)))
        })
    }

    fun select(activity: Activity, tag: String) {
        require(tag in options)
        if (tag == current(activity)) return
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        } else {
            activity.getSharedPreferences("language", Context.MODE_PRIVATE).edit().putString("tag", tag).apply()
            activity.recreate()
        }
    }
}
