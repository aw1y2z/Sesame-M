package io.github.aw1y2z.sesame.ui.miuix

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import io.github.aw1y2z.sesame.data.AppConfig
import io.github.aw1y2z.sesame.data.ViewAppInfo
import io.github.aw1y2z.sesame.util.LanguageUtil
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

open class MiuixBaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageUtil.setLocal(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppConfig.load()
        ViewAppInfo.init(applicationContext)
        setupSystemBars()
    }

    /**
     * 所有 miuix 页面统一用此方法设置内容,保证深色模式跟随 AppConfig。
     */
    protected fun setAppContent(content: @Composable () -> Unit) {
        setContent {
            val colorSchemeMode = when {
                AppConfig.INSTANCE.followSystem != false -> ColorSchemeMode.System
                AppConfig.INSTANCE.darkMode == true -> ColorSchemeMode.Dark
                else -> ColorSchemeMode.Light
            }
            val controller = remember {
                ThemeController(colorSchemeMode = colorSchemeMode)
            }
            MiuixTheme(controller) {
                content()
            }
        }
    }

    private fun setupSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val isDark = if (AppConfig.INSTANCE.followSystem != false) {
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        } else {
            AppConfig.INSTANCE.darkMode == true
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }
}
