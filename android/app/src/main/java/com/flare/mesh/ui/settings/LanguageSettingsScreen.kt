package com.flare.mesh.ui.settings

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.flare.mesh.R
import com.flare.mesh.util.Constants
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSettingsScreen(
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val currentLocale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    var selectedLanguage by remember { mutableStateOf(getCurrentLanguageCode(context)) }
    var pendingLanguage by remember { mutableStateOf<Constants.LanguageOption?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.language_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.language_restart_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            Constants.SUPPORTED_LANGUAGES.forEach { lang ->
                val isSelected = selectedLanguage == lang.code
                ListItem(
                    modifier = Modifier.clickable {
                        if (lang.code != selectedLanguage) {
                            pendingLanguage = lang
                        }
                    },
                    headlineContent = {
                        Text(
                            text = stringResource(lang.nameRes),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    supportingContent = if (lang.nativeName != null) {
                        { Text(lang.nativeName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } else null,
                    trailingContent = {
                        if (isSelected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
    }

    // Confirmation dialog
    pendingLanguage?.let { lang ->
        val langName = lang.nativeName ?: stringResource(lang.nameRes)
        AlertDialog(
            onDismissRequest = { pendingLanguage = null },
            confirmButton = {
                TextButton(onClick = {
                    selectedLanguage = lang.code
                    pendingLanguage = null
                    onNavigateBack()
                    setAppLanguage(context, lang.code)
                }) {
                    Text(stringResource(R.string.language_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingLanguage = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            title = { Text(stringResource(R.string.language_confirm_title)) },
            text = { Text(stringResource(R.string.language_confirm_message, langName)) },
        )
    }
}

private fun getCurrentLanguageCode(context: Context): String {
    val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getString(Constants.KEY_LANGUAGE, Constants.LANGUAGE_SYSTEM_DEFAULT) ?: Constants.LANGUAGE_SYSTEM_DEFAULT
}

private fun setAppLanguage(context: Context, languageCode: String) {
    val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().putString(Constants.KEY_LANGUAGE, languageCode).apply()

    if (languageCode == Constants.LANGUAGE_SYSTEM_DEFAULT) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    } else {
        val localeList = LocaleListCompat.forLanguageTags(languageCode)
        AppCompatDelegate.setApplicationLocales(localeList)
    }
}
