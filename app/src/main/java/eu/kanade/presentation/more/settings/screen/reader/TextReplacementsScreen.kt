package eu.kanade.presentation.more.settings.screen.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.category.components.CategoryFloatingActionButton
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.text.TextReplacement
import eu.kanade.tachiyomi.ui.reader.text.TextReplacements
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Delete
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * The reader's text replacements: words and lines changed or removed in every chapter, in the order they apply.
 */
class TextReplacementsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val preference = remember { Injekt.get<ReaderPreferences>().textReplacements() }
        val stored by preference.collectAsState()
        val rules = remember(stored) { TextReplacements.decode(stored) }
        fun save(new: List<TextReplacement>) = preference.set(TextReplacements.encode(new))

        // Index of the rule being edited, or -1 for a new one; null when no dialog is open.
        var editing by rememberSaveable { mutableStateOf<Int?>(null) }
        val lazyListState = rememberLazyListState()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    navigateUp = navigator::pop,
                    title = stringResource(KMR.strings.reader_text_replacements),
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                CategoryFloatingActionButton(lazyListState = lazyListState, onCreate = { editing = -1 })
            },
        ) { paddingValues ->
            if (rules.isEmpty()) {
                EmptyScreen(KMR.strings.reader_text_replacements_empty, modifier = Modifier.padding(paddingValues))
                return@Scaffold
            }
            LazyColumn(
                state = lazyListState,
                contentPadding = PaddingValues(
                    top = paddingValues.calculateTopPadding() + MaterialTheme.padding.small,
                    bottom = paddingValues.calculateBottomPadding() + MaterialTheme.padding.small,
                    start = MaterialTheme.padding.medium,
                    end = MaterialTheme.padding.medium,
                ),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                itemsIndexed(rules) { index, rule ->
                    RuleItem(
                        rule = rule,
                        onClick = { editing = index },
                        onToggle = { enabled ->
                            save(rules.toMutableList().apply { set(index, rule.copy(enabled = enabled)) })
                        },
                        onDelete = { save(rules.toMutableList().apply { removeAt(index) }) },
                    )
                }
            }
        }

        editing?.let { index ->
            RuleDialog(
                initial = rules.getOrNull(index),
                onDismissRequest = { editing = null },
                onConfirm = { rule ->
                    save(if (index in rules.indices) rules.toMutableList().apply { set(index, rule) } else rules + rule)
                    editing = null
                },
            )
        }
    }
}

@Composable
private fun RuleItem(
    rule: TextReplacement,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(start = MaterialTheme.padding.medium, end = MaterialTheme.padding.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f).padding(vertical = MaterialTheme.padding.medium),
            ) {
                Text(
                    text = rule.find,
                    fontFamily = if (rule.regex) FontFamily.Monospace else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (rule.replace.isEmpty()) {
                        stringResource(KMR.strings.reader_text_replacements_removed)
                    } else {
                        "→ ${rule.replace}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(checked = rule.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) {
                Icon(MaterialSymbols.Rounded.Delete, contentDescription = stringResource(MR.strings.action_delete))
            }
        }
    }
}

@Composable
private fun RuleDialog(
    initial: TextReplacement?,
    onDismissRequest: () -> Unit,
    onConfirm: (TextReplacement) -> Unit,
) {
    var find by rememberSaveable { mutableStateOf(initial?.find.orEmpty()) }
    var replace by rememberSaveable { mutableStateOf(initial?.replace.orEmpty()) }
    var regex by rememberSaveable { mutableStateOf(initial?.regex ?: false) }
    val regexError = if (regex && find.isNotEmpty()) TextReplacements.regexError(find) else null

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                stringResource(
                    if (initial == null) KMR.strings.reader_text_replacements_add else KMR.strings.reader_text_replacements_edit,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                OutlinedTextField(
                    value = find,
                    onValueChange = { find = it },
                    label = { Text(stringResource(KMR.strings.reader_text_replacements_find)) },
                    isError = regexError != null,
                    supportingText = regexError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = replace,
                    onValueChange = { replace = it },
                    label = { Text(stringResource(KMR.strings.reader_text_replacements_replace)) },
                    supportingText = { Text(stringResource(KMR.strings.reader_text_replacements_replace_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { regex = !regex },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = regex, onCheckedChange = { regex = it })
                    Text(stringResource(KMR.strings.reader_text_replacements_regex))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(TextReplacement(find, replace, regex, enabled = initial?.enabled ?: true))
                },
                enabled = find.isNotEmpty() && regexError == null,
            ) {
                Text(stringResource(MR.strings.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}
