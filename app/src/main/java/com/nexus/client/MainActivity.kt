package com.nexus.client

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.nexus.client.core.routing.OverrideParser
import com.nexus.client.core.routing.RouteAction
import com.nexus.client.core.routing.RoutingProfile
import com.nexus.client.core.awg.ConfigImport
import com.nexus.client.core.awg.ImportedConfig
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.nexus.client.data.ProfileMeta
import com.nexus.client.diagnostics.CheckLevel
import com.nexus.client.diagnostics.DiagnosticReport
import com.nexus.client.diagnostics.DiagnosticsRunner
import com.nexus.client.diagnostics.DiagnosticsRunner.formatBytes
import com.nexus.client.diagnostics.DeviceStabilitySettings
import com.nexus.client.routing.RoutingRepository.Companion.DEFAULT_SOURCE
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nexus.client.tunnel.ConnectionPhase
import com.nexus.client.update.AppUpdatePhase
import com.nexus.client.update.AppUpdateState
import com.nexus.client.ui.NexusMint
import com.nexus.client.ui.NexusAmber
import com.nexus.client.ui.NexusPrimary
import com.nexus.client.ui.NexusTheme
import com.nexus.client.ui.NexusBackdrop
import com.nexus.client.ui.ConnectionHalo
import com.nexus.client.ui.ExWgOutline
import com.nexus.client.ui.ExWgPower
import com.nexus.client.ui.ExWgProfileGlyph
import com.nexus.client.ui.ExWgRaised
import com.nexus.client.ui.ExWgWordmark
import com.nexus.client.ui.activeTunnelWarning
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import org.json.JSONObject
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NexusTheme { NexusApp(application as NexusApplication) } }
    }

    override fun onResume() {
        super.onResume()
        (application as? NexusApplication)?.updates?.onHostResume(this)
    }
}

private enum class AppLanguage(val code: String) {
    RU("ru"),
    EN("en");

    companion object {
        fun fromCode(code: String): AppLanguage =
            if (code.trim().equals("en", ignoreCase = true)) EN else RU
    }
}

private val LocalAppLanguage = compositionLocalOf { AppLanguage.RU }

private fun tr(lang: AppLanguage, ru: String, en: String): String =
    if (lang == AppLanguage.EN) en else ru

@Composable
private fun tr(ru: String, en: String): String =
    tr(LocalAppLanguage.current, ru, en)

private enum class Section(val titleRu: String, val titleEn: String) {
    HOME("Главная", "Home"),
    ROUTING("Правила", "Rules"),
    DIAGNOSTICS("Диагностика", "Diagnostics");

    fun title(lang: AppLanguage): String = tr(lang, titleRu, titleEn)
}

private enum class OverrideAction(val wire: String, val title: String, val routeAction: RouteAction) {
    DIRECT("direct", "Direct", RouteAction.DIRECT),
    PROXY("proxy", "Proxy", RouteAction.PROXY),
    BLOCK("block", "Block", RouteAction.BLOCK);
}

private data class EffectiveRuleItem(
    val value: String,
    val effectiveAction: OverrideAction,
    val originAction: OverrideAction?,
) {
    val overridden: Boolean get() = originAction != effectiveAction
}

private fun effectiveCategoryRules(
    category: OverrideAction,
    profile: RoutingProfile,
    overridesText: String,
    searchQuery: String,
): List<EffectiveRuleItem> {
    val parsedOverrides = runCatching { OverrideParser.parse(overridesText) }.getOrNull()
    val overrideEntries = mutableListOf<Pair<String, OverrideAction>>()
    if (parsedOverrides != null) {
        (parsedOverrides.directSites + parsedOverrides.directIp).forEach { overrideEntries += it to OverrideAction.DIRECT }
        (parsedOverrides.proxySites + parsedOverrides.proxyIp).forEach { overrideEntries += it to OverrideAction.PROXY }
        (parsedOverrides.blockSites + parsedOverrides.blockIp).forEach { overrideEntries += it to OverrideAction.BLOCK }
    }

    val overrideMap = linkedMapOf<String, OverrideAction>()
    overrideEntries.forEach { (rawValue, action) ->
        val key = rawValue.trim().lowercase()
        if (key !in overrideMap) {
            overrideMap[key] = action
        }
    }

    val originMap = linkedMapOf<String, Pair<String, OverrideAction>>()
    fun registerOrigin(items: List<String>, origin: OverrideAction) {
        items.forEach { raw ->
            val trimmed = raw.trim()
            if (trimmed.isNotEmpty()) {
                val key = trimmed.lowercase()
                if (key !in originMap) {
                    originMap[key] = trimmed to origin
                }
            }
        }
    }
    registerOrigin(profile.directSites + profile.directIp, OverrideAction.DIRECT)
    registerOrigin(profile.proxySites + profile.proxyIp, OverrideAction.PROXY)
    registerOrigin(profile.blockSites + profile.blockIp, OverrideAction.BLOCK)

    val result = mutableListOf<EffectiveRuleItem>()
    val seen = hashSetOf<String>()

    // 1. Local overrides that target this category come first so the user sees them immediately.
    overrideEntries.forEach { (rawValue, action) ->
        val key = rawValue.trim().lowercase()
        if (action == category && seen.add(key)) {
            val origin = originMap[key]?.second
            val displayValue = originMap[key]?.first ?: rawValue.trim()
            result += EffectiveRuleItem(
                value = displayValue,
                effectiveAction = category,
                originAction = origin,
            )
        }
    }

    // 2. Profile rules originally in this category that were not moved to another category.
    val profileItems = when (category) {
        OverrideAction.DIRECT -> profile.directSites + profile.directIp
        OverrideAction.PROXY -> profile.proxySites + profile.proxyIp
        OverrideAction.BLOCK -> profile.blockSites + profile.blockIp
    }
    profileItems.forEach { raw ->
        val trimmed = raw.trim()
        val key = trimmed.lowercase()
        val effective = overrideMap[key] ?: category
        if (effective == category && seen.add(key)) {
            result += EffectiveRuleItem(
                value = trimmed,
                effectiveAction = category,
                originAction = category,
            )
        }
    }

    val query = searchQuery.trim()
    return if (query.isEmpty()) result else result.filter { it.value.contains(query, ignoreCase = true) }
}

private fun formatRuleOrder(profile: RoutingProfile, lang: AppLanguage): String {
    val orderLabel = profile.routeOrder.joinToString(" -> ") { action ->
        when (action) {
            RouteAction.DIRECT -> "Direct"
            RouteAction.PROXY -> "Proxy"
            RouteAction.BLOCK -> "Block"
        }
    }
    val defaultRoute = if (profile.globalProxy) {
        tr(lang, "По умолчанию в VPN (Proxy)", "Default via VPN (Proxy)")
    } else {
        tr(lang, "По умолчанию напрямую (Direct)", "Default direct (Direct)")
    }
    return "$orderLabel · $defaultRoute"
}

private fun formatDomainStrategy(strategy: String, lang: AppLanguage): String = when (strategy.trim().lowercase()) {
    "ipifnonmatch" -> tr(lang, "Сначала домен, при промахе IP (IPIfNonMatch)", "Domain first, then IP (IPIfNonMatch)")
    "ipondemand" -> tr(lang, "Запрашивать IP при проверке (IPOnDemand)", "Resolve IP on demand (IPOnDemand)")
    "asis" -> tr(lang, "Только правила доменов и IP (AsIs)", "Domain and IP rules only (AsIs)")
    else -> strategy.ifBlank { "IPIfNonMatch" }
}

private fun formatEpochMillis(epochMillis: Long, lang: AppLanguage): String {
    if (epochMillis <= 0L) return tr(lang, "Встроенный набор", "Built-in bundle")
    return runCatching {
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(epochMillis))
    }.getOrDefault("—")
}

private fun formatProfileTimestamp(raw: String, lang: AppLanguage): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed == "0") return tr(lang, "Не указана", "Not specified")
    val seconds = trimmed.toLongOrNull() ?: return trimmed
    val millis = if (seconds > 1_000_000_000_000L) seconds else seconds * 1000L
    return runCatching {
        SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(millis))
    }.getOrDefault(trimmed)
}

private fun formatDnsProtocol(rawType: String, server: String, domain: String): String {
    val normalized = rawType.trim().lowercase()
    return when {
        normalized == "doh" || server.startsWith("https://", true) || domain.startsWith("https://", true) -> "DoH (HTTPS)"
        normalized == "dot" || server.startsWith("tls://", true) || domain.startsWith("tls://", true) -> "DoT (TLS)"
        normalized == "tcp" || server.startsWith("tcp://", true) -> "TCP"
        else -> "UDP"
    }
}

private fun extractHostLabel(rawUrl: String, lang: AppLanguage): String {
    return runCatching { URI(rawUrl.trim()).host.orEmpty() }.getOrDefault("").ifBlank { tr(lang, "Встроенная база", "Built-in DB") }
}

private val fallbackDnsKeys = setOf("dns-direct-fallback", "dns-proxy-fallback")

private fun isFallbackDnsEnabled(source: String): Boolean {
    val keys = source.lines().map { it.trim().substringBefore(' ').lowercase() }.toSet()
    return fallbackDnsKeys.all(keys::contains)
}

private fun setFallbackDns(source: String, enabled: Boolean): String {
    val retained = source.lines().filter { line -> line.trim().substringBefore(' ').lowercase() !in fallbackDnsKeys }
    val additions = if (enabled) listOf(
        "dns-direct-fallback tls://common.dot.dns.yandex.net 77.88.8.8",
        "dns-proxy-fallback https://common.dot.dns.yandex.net/dns-query 77.88.8.8",
    ) else emptyList()
    return (retained + additions).filter(String::isNotBlank).joinToString("\n")
}

private fun rulesOnly(source: String): String = source.lines().filter { line ->
    val trimmed = line.trim()
    trimmed.isNotEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("dns-", ignoreCase = true)
}.joinToString("\n")

private fun replaceRulesOnly(source: String, rules: String): String {
    val hidden = source.lines().filter {
        val trimmed = it.trim()
        trimmed.startsWith("dns-", ignoreCase = true) || trimmed.startsWith("#")
    }
    val editedRules = rules.lines().map(String::trim).filter(String::isNotBlank)
    return (hidden + editedRules).joinToString("\n")
}

private fun categoryCountLabel(count: Int, lang: AppLanguage): String {
    if (lang == AppLanguage.EN) {
        return if (count == 1) "1 rule" else "$count rules"
    }
    val suffix = when {
        count % 100 in 11..14 -> "правил"
        count % 10 == 1 -> "правило"
        count % 10 in 2..4 -> "правила"
        else -> "правил"
    }
    return "$count $suffix"
}

private val PressEaseOut = CubicBezierEasing(0.2f, 0f, 0f, 1f)

@Composable
private fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f
        }.getOrDefault(true)
    }
}

@Composable
private fun rememberPressScale(
    interactionSource: MutableInteractionSource,
    enabled: Boolean,
    pressedScale: Float = 0.96f,
): Float {
    val motionEnabled = rememberMotionEnabled()
    val pressed by interactionSource.collectIsPressedAsState()
    val target = if (pressed && enabled && motionEnabled) pressedScale else 1f
    val scale by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 150, easing = PressEaseOut),
        label = "pressScale",
    )
    return scale
}

@Composable
private fun VersionBadge(
    updateState: AppUpdateState,
    onVersionTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val checking = updateState.phase is AppUpdatePhase.Checking
    val available = updateState.info?.available == true
    val desc = tr(
        "Версия приложения v${BuildConfig.VERSION_NAME}. Нажмите 1 раз для проверки обновлений, 5 раз — для режима диагностики",
        "App version v${BuildConfig.VERSION_NAME}. Tap once to check for updates, 5 times to toggle diagnostics",
    )
    Surface(
        onClick = onVersionTap,
        modifier = modifier.semantics {
            role = Role.Button
            contentDescription = desc
        },
        shape = RoundedCornerShape(999.dp),
        color = if (available) Color(0xFFDDF3EC) else Color(0xCCFFFFFF),
        border = BorderStroke(1.dp, if (available) Color(0x6614967B) else Color(0xFFD7CFC2)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (checking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.4.dp,
                    color = Color(0xFF136952),
                )
            }
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                color = if (available) Color(0xFF136952) else Color(0xFF6E6B65),
                fontSize = 11.sp,
                fontWeight = if (available) FontWeight.SemiBold else FontWeight.Medium,
                style = TextStyle(fontFeatureSettings = "tnum"),
            )
        }
    }
}

@Composable
private fun LanguageToggle(
    language: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val desc = tr("Язык интерфейса", "Interface language")
    val density = LocalDensity.current
    val segmentWidth = 32.dp
    val maxOffsetPx = with(density) { segmentWidth.toPx() }
    var dragging by remember { mutableStateOf(false) }
    var dragOffsetPx by remember { mutableStateOf(0f) }

    val baseOffsetDp by animateDpAsState(
        targetValue = if (language == AppLanguage.RU) 0.dp else segmentWidth,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 520f),
        label = "langThumbOffset",
    )
    val thumbOffsetPx = if (dragging) {
        dragOffsetPx.coerceIn(0f, maxOffsetPx)
    } else {
        with(density) { baseOffsetDp.toPx() }
    }
    val activeByThumb = if (thumbOffsetPx < maxOffsetPx * 0.5f) AppLanguage.RU else AppLanguage.EN

    Surface(
        modifier = modifier
            .width(68.dp)
            .height(30.dp)
            .semantics { contentDescription = desc }
            .pointerInput(language, maxOffsetPx) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragging = true
                        dragOffsetPx = if (language == AppLanguage.RU) 0f else maxOffsetPx
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        dragOffsetPx = (dragOffsetPx + dragAmount).coerceIn(0f, maxOffsetPx)
                    },
                    onDragEnd = {
                        val next = if (dragOffsetPx >= maxOffsetPx * 0.5f) AppLanguage.EN else AppLanguage.RU
                        dragging = false
                        onSelect(next)
                    },
                    onDragCancel = {
                        dragging = false
                    },
                )
            },
        shape = RoundedCornerShape(999.dp),
        color = Color(0xCCD8D1C5),
        border = BorderStroke(1.dp, Color(0xCCFFFFFF)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(2.dp),
        ) {
            Surface(
                modifier = Modifier
                    .width(segmentWidth)
                    .fillMaxHeight()
                    .offset { IntOffset(thumbOffsetPx.roundToInt(), 0) },
                shape = RoundedCornerShape(999.dp),
                color = Color.White,
                shadowElevation = 2.dp,
            ) {}
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppLanguage.entries.forEach { item ->
                    val selected = item == activeByThumb
                    val segLabel = if (item == AppLanguage.RU) "RU" else "EN"
                    val textColor by animateColorAsState(
                        targetValue = if (selected) Color(0xFF17181A) else Color(0xFF6B675E),
                        animationSpec = tween(durationMillis = 160),
                        label = "langTextColor",
                    )
                    Box(
                        modifier = Modifier
                            .width(segmentWidth)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onSelect(item) },
                            )
                            .semantics {
                                role = Role.RadioButton
                                this.selected = item == language
                                contentDescription = segLabel
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = segLabel,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = textColor,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NexusApp(app: NexusApplication) {
    var language by remember { mutableStateOf(AppLanguage.fromCode(app.profiles.appLanguage())) }
    val onLanguageChange: (AppLanguage) -> Unit = { next ->
        if (language != next) {
            language = next
            app.profiles.saveAppLanguage(next.code)
        }
    }
    var section by remember { mutableStateOf(Section.HOME) }
    val diagnosticsUnlocked by EventLog.enabled.collectAsState()
    val updateState by app.updates.state.collectAsState()
    val visibleSections = remember(diagnosticsUnlocked) {
        if (diagnosticsUnlocked) Section.entries else listOf(Section.HOME, Section.ROUTING)
    }
    LaunchedEffect(diagnosticsUnlocked) {
        if (!diagnosticsUnlocked && section == Section.DIAGNOSTICS) {
            section = Section.HOME
        }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val onCheckUpdates: () -> Unit = {
        val currentInfo = updateState.info
        if (currentInfo?.available == true && !updateState.dismissedForSession) {
            val phase = updateState.phase
            if (phase is AppUpdatePhase.ReadyToInstall) {
                app.updates.promptInstall(context, phase.apkFile)
            } else {
                app.updates.startDownloadAndInstall(context)
            }
        } else {
            app.updates.checkForUpdates(silent = false) { info, error ->
                val msg = when {
                    error != null -> tr(
                        language,
                        "Не удалось проверить обновления: ${error.message ?: error.javaClass.simpleName}",
                        "Failed to check for updates: ${error.message ?: error.javaClass.simpleName}",
                    )
                    info?.available == true -> tr(
                        language,
                        "Доступна новая версия v${info.latestVersion}",
                        "New version v${info.latestVersion} is available",
                    )
                    else -> tr(
                        language,
                        "Установлена актуальная версия (v${BuildConfig.VERSION_NAME})",
                        "You have the latest version (v${BuildConfig.VERSION_NAME})",
                    )
                }
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    var versionTapCount by remember { mutableStateOf(0) }
    var lastVersionTapMs by remember { mutableStateOf(0L) }
    var singleTapUpdateJob by remember { mutableStateOf<Job?>(null) }
    val onVersionTap: () -> Unit = {
        val now = android.os.SystemClock.elapsedRealtime()
        versionTapCount = if (now - lastVersionTapMs > 2500L) 1 else versionTapCount + 1
        lastVersionTapMs = now
        singleTapUpdateJob?.cancel()
        if (versionTapCount >= 5) {
            versionTapCount = 0
            val nextEnabled = !EventLog.isEnabled
            EventLog.setEnabled(nextEnabled)
            if (nextEnabled) {
                section = Section.DIAGNOSTICS
                android.widget.Toast.makeText(
                    context,
                    tr(language, "Режим диагностики открыт", "Diagnostics mode unlocked"),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            } else {
                if (section == Section.DIAGNOSTICS) section = Section.HOME
                android.widget.Toast.makeText(
                    context,
                    tr(language, "Режим диагностики скрыт", "Diagnostics mode hidden"),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        } else if (versionTapCount == 1) {
            singleTapUpdateJob = scope.launch {
                delay(380L)
                versionTapCount = 0
                onCheckUpdates()
            }
        }
    }
    val onHideDiagnostics: () -> Unit = {
        singleTapUpdateJob?.cancel()
        versionTapCount = 0
        EventLog.setEnabled(false)
        section = Section.HOME
        android.widget.Toast.makeText(
            context,
            tr(language, "Режим диагностики скрыт", "Diagnostics mode hidden"),
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }
    CompositionLocalProvider(LocalAppLanguage provides language) {
        Box(Modifier.fillMaxSize()) {
            NexusBackdrop(Modifier.fillMaxSize())
            BoxWithConstraints(Modifier.fillMaxSize()) {
                if (maxWidth >= 720.dp) {
                    Row(Modifier.fillMaxSize()) {
                        ExWgSideNavigation(
                            section = section,
                            sections = visibleSections,
                            updateState = updateState,
                            onSelect = { section = it },
                            onVersionTap = onVersionTap,
                        )
                        AppPage(
                            app = app,
                            section = section,
                            language = language,
                            onLanguageChange = onLanguageChange,
                            onVersionTap = onVersionTap,
                            onHideDiagnostics = onHideDiagnostics,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    Scaffold(
                        containerColor = Color.Transparent,
                        bottomBar = {
                            ExWgBottomNavigation(
                                section = section,
                                sections = visibleSections,
                                onSelect = { section = it },
                            )
                        },
                    ) { padding ->
                        AppPage(
                            app = app,
                            section = section,
                            language = language,
                            onLanguageChange = onLanguageChange,
                            onVersionTap = onVersionTap,
                            onHideDiagnostics = onHideDiagnostics,
                            modifier = Modifier.padding(padding),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExWgBottomNavigation(
    section: Section,
    sections: List<Section>,
    onSelect: (Section) -> Unit,
) {
    val lang = LocalAppLanguage.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Color(0xD9F8F5EF),
            border = BorderStroke(1.dp, Color(0xEBFFFFFF)),
            shadowElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                sections.forEach { item ->
                    val selected = item == section
                    val activeBg = if (selected) Color.White else Color.Transparent
                    val contentColor = if (selected) Color(0xFF17181A) else Color(0xFF6E6B65)
                    val title = item.title(lang)
                    Surface(
                        onClick = { onSelect(item) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .semantics {
                                role = Role.Tab
                                this.selected = selected
                                contentDescription = title
                            },
                        shape = RoundedCornerShape(24.dp),
                        color = activeBg,
                        shadowElevation = if (selected) 2.dp else 0.dp,
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            SectionIcon(item, selected)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                title,
                                color = contentColor,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExWgSideNavigation(
    section: Section,
    sections: List<Section>,
    updateState: AppUpdateState,
    onSelect: (Section) -> Unit,
    onVersionTap: () -> Unit,
) {
    val lang = LocalAppLanguage.current
    Surface(
        modifier = Modifier.width(224.dp).fillMaxHeight(),
        color = Color(0xD9F8F5EF),
        border = BorderStroke(1.dp, Color(0xEBFFFFFF)),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 30.dp)) {
            ExWgWordmark(fontSize = 28.sp)
            Row(
                modifier = Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                VersionBadge(updateState = updateState, onVersionTap = onVersionTap)
            }
            Spacer(Modifier.height(32.dp))
            sections.forEach { item ->
                val selected = item == section
                val title = item.title(lang)
                Surface(
                    onClick = { onSelect(item) },
                    modifier = Modifier.fillMaxWidth().height(52.dp).semantics {
                        role = Role.Tab
                        this.selected = selected
                        contentDescription = title
                    },
                    shape = RoundedCornerShape(26.dp),
                    color = if (selected) Color.White else Color.Transparent,
                    border = if (selected) BorderStroke(1.dp, Color(0xEBFFFFFF)) else null,
                    shadowElevation = if (selected) 2.dp else 0.dp,
                ) {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        SectionIcon(item, selected)
                        Spacer(Modifier.width(14.dp))
                        Text(
                            title,
                            color = if (selected) Color(0xFF17181A) else Color(0xFF6E6B65),
                            fontSize = 15.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun SectionIcon(section: Section, selected: Boolean = false) {
    val color = if (selected) Color(0xFF17181A) else Color(0xFF6E6B65)
    Canvas(Modifier.size(24.dp)) {
        val w = size.width; val h = size.height
        val stroke = if (selected) 2.dp.toPx() else 1.6.dp.toPx()
        when (section) {
            Section.HOME -> {
                val roof = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * .14f, h * .48f)
                    lineTo(w * .5f, h * .17f)
                    lineTo(w * .86f, h * .48f)
                    moveTo(w * .23f, h * .42f)
                    lineTo(w * .23f, h * .84f)
                    lineTo(w * .43f, h * .84f)
                    lineTo(w * .43f, h * .61f)
                    lineTo(w * .59f, h * .61f)
                    lineTo(w * .59f, h * .84f)
                    lineTo(w * .77f, h * .84f)
                    lineTo(w * .77f, h * .42f)
                }
                drawPath(roof, color, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            }
            Section.ROUTING -> {
                listOf(.27f, .5f, .73f).forEach { y ->
                    drawCircle(color, radius = 1.6.dp.toPx(), center = Offset(w * .2f, h * y))
                    drawLine(color, Offset(w * .34f, h * y), Offset(w * .82f, h * y), strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
            Section.DIAGNOSTICS -> {
                val pulse = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * .08f, h * .58f)
                    lineTo(w * .28f, h * .58f)
                    lineTo(w * .39f, h * .28f)
                    lineTo(w * .53f, h * .78f)
                    lineTo(w * .65f, h * .46f)
                    lineTo(w * .9f, h * .46f)
                }
                drawPath(pulse, color, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            }
        }
    }
}

@Composable
private fun AppPage(
    app: NexusApplication,
    section: Section,
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    onVersionTap: () -> Unit,
    onHideDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val updateState by app.updates.state.collectAsState()
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        if (section != Section.HOME) {
            Row(
                Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ExWgWordmark(fontSize = 24.sp)
                Spacer(Modifier.width(6.dp))
                VersionBadge(updateState = updateState, onVersionTap = onVersionTap)
                Spacer(Modifier.weight(1f))
                LanguageToggle(language = language, onSelect = onLanguageChange)
            }
        }
        AnimatedContent(
            targetState = section,
            transitionSpec = {
                fadeIn(tween(140, easing = PressEaseOut)) togetherWith fadeOut(tween(90, easing = PressEaseOut))
            },
            label = "section",
        ) { target ->
            when (target) {
                Section.HOME -> HomeScreen(
                    app = app,
                    language = language,
                    onLanguageChange = onLanguageChange,
                    onVersionTap = onVersionTap,
                )
                Section.ROUTING -> RoutingScreen(app)
                Section.DIAGNOSTICS -> DiagnosticsScreen(app = app, onHideDiagnostics = onHideDiagnostics)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    app: NexusApplication,
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    onVersionTap: () -> Unit,
) {
    val lang = LocalAppLanguage.current
    var profiles by remember { mutableStateOf(app.profiles.list()) }
    var activeId by remember { mutableStateOf(app.profiles.activeId()) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ProfileMeta?>(null) }
    var deletingProfile by remember { mutableStateOf<ProfileMeta?>(null) }
    var dialogConfig by remember { mutableStateOf("") }
    var importBusy by remember { mutableStateOf(false) }
    var addMenuExpanded by remember { mutableStateOf(false) }
    var imports by remember { mutableStateOf<List<ImportedConfig>>(emptyList()) }
    var importName by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snapshot by app.tunnel.state.collectAsState()
    val updateState by app.updates.state.collectAsState()
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(snapshot.phase) {
        if (snapshot.phase == ConnectionPhase.CONNECTED && android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val active = profiles.firstOrNull { it.id == activeId }
    var pendingConfig by remember { mutableStateOf<String?>(null) }

    fun reload() { profiles = app.profiles.list(); activeId = app.profiles.activeId() }
    fun startConnection(raw: String) {
        scope.launch { app.tunnel.connect(raw).onFailure { snackbar.showSnackbar("${it.javaClass.simpleName}: ${it.message}") } }
    }
    fun prepareImport(read: () -> List<ImportedConfig>) {
        if (importBusy || app.tunnel.state.value.phase !in setOf(ConnectionPhase.DISCONNECTED, ConnectionPhase.ERROR)) return
        importBusy = true
        scope.launch {
            try {
                val candidates = withContext(Dispatchers.IO) {
                    app.profiles.prewarmCrypto()
                    app.profiles.validateImports(read())
                }
                if (app.tunnel.state.value.phase in setOf(ConnectionPhase.DISCONNECTED, ConnectionPhase.ERROR)) {
                    imports = candidates; importName = candidates.first().name
                }
            } catch (error: Exception) {
                EventLog.error("config.import.read", error)
                snackbar.showSnackbar("${error.javaClass.simpleName}: ${error.message}")
            } finally { importBusy = false }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            EventLog.add("VPN-разрешение выдано")
            pendingConfig?.let(::startConnection)
        } else scope.launch { snackbar.showSnackbar(tr(lang, "VPN-разрешение не выдано", "VPN permission denied")) }
        pendingConfig = null
    }
    fun requestConnection(profile: ProfileMeta) {
        if (profile.id != activeId) {
            app.profiles.select(profile.id)
            reload()
        }
        runCatching { app.profiles.readConfig(profile.id) }.onSuccess { raw ->
            val permission = VpnService.prepare(context)
            if (permission == null) startConnection(raw) else { pendingConfig = raw; permissionLauncher.launch(permission) }
        }.onFailure { scope.launch { snackbar.showSnackbar(it.message ?: tr(lang, "Не удалось открыть профиль", "Failed to open profile")) } }
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) prepareImport {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "Ex-WG.conf"
            requireNotNull(context.contentResolver.openInputStream(uri)) { tr(lang, "Не удалось открыть файл", "Failed to open file") }.use { ConfigImport.fromFile(it, name) }
        }
    }
    val qrLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { text -> prepareImport { listOf(ConfigImport.fromQr(text)) } }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExWgWordmark(fontSize = 24.sp)
            Spacer(Modifier.width(6.dp))
            VersionBadge(updateState = updateState, onVersionTap = onVersionTap)
            Spacer(Modifier.weight(1f))
            LanguageToggle(language = language, onSelect = onLanguageChange)
            Spacer(Modifier.width(10.dp))
            Box {
                val importEnabled = !importBusy && snapshot.phase in setOf(ConnectionPhase.DISCONNECTED, ConnectionPhase.ERROR)
                val addInteraction = remember { MutableInteractionSource() }
                val addScale = rememberPressScale(addInteraction, importEnabled)
                val addDesc = tr("Добавить профиль VPN", "Add VPN profile")
                Surface(
                    onClick = { addMenuExpanded = true },
                    enabled = importEnabled,
                    interactionSource = addInteraction,
                    modifier = Modifier.requiredSize(44.dp).graphicsLayer {
                        scaleX = addScale
                        scaleY = addScale
                    }.semantics {
                        role = Role.Button
                        contentDescription = addDesc
                    },
                    shape = CircleShape,
                    color = Color.White,
                    border = BorderStroke(1.dp, Color(0xFFDED8CE)),
                    shadowElevation = 2.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (importBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 1.6.dp, color = Color(0xFF17181A))
                        else Text("+", fontSize = 24.sp, fontWeight = FontWeight.Normal, color = if (importEnabled) Color(0xFF17181A) else Color(0xFF6E6B65))
                    }
                }
                DropdownMenu(expanded = addMenuExpanded, onDismissRequest = { addMenuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(tr("Сканировать QR-код", "Scan QR code")) },
                        onClick = {
                            addMenuExpanded = false
                            qrLauncher.launch(
                                ScanOptions()
                                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    .setCaptureActivity(QrScanActivity::class.java)
                                    .setPrompt(tr(lang, "Наведите камеру на QR конфигурации AWG", "Point the camera at an AWG QR code"))
                                    .setBeepEnabled(false)
                                    .setBarcodeImageEnabled(false),
                            )
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Импортировать .conf / .zip", "Import .conf / .zip")) },
                        onClick = { addMenuExpanded = false; fileLauncher.launch(arrayOf("*/*")) },
                    )
                }
            }
        }
        if (updateState.info?.available == true && !updateState.dismissedForSession) {
            AppUpdateBannerCard(
                state = updateState,
                onAction = {
                    val phase = updateState.phase
                    if (phase is AppUpdatePhase.ReadyToInstall) {
                        app.updates.promptInstall(context, phase.apkFile)
                    } else {
                        app.updates.startDownloadAndInstall(context)
                    }
                },
                onCancel = { app.updates.cancelOrDismiss() },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        BoxWithConstraints(Modifier.weight(1f)) {
        val heroSize = (maxWidth * .61f).coerceIn(188.dp, 226.dp)
        val warning = activeTunnelWarning(snapshot)
        val dynamicContent = (if (snapshot.tunnelActive) 84.dp else 0.dp) +
            (if (snapshot.phase == ConnectionPhase.WAITING && snapshot.lastHandshakeEpochSeconds == 0L) 42.dp else 0.dp) +
            (if (warning != null) 56.dp else 0.dp) +
            (if (updateState.info?.available == true && !updateState.dismissedForSession) 76.dp else 0.dp)
        val profileGap = (maxHeight - heroSize - 390.dp - dynamicContent).coerceIn(12.dp, 150.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            val confirmedBefore = snapshot.tunnelActive && snapshot.lastHandshakeEpochSeconds > 0
            Spacer(Modifier.height(14.dp))
            ConnectionButton(snapshot.phase, confirmedBefore, enabled = (active != null && !importBusy && imports.isEmpty()), cancellable = snapshot.tunnelActive, diameter = heroSize) {
                if (snapshot.tunnelActive) {
                    scope.launch { app.tunnel.disconnect() }
                } else if (active != null) {
                    requestConnection(active)
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                if (confirmedBefore && snapshot.phase !in setOf(ConnectionPhase.DISCONNECTING, ConnectionPhase.ERROR)) {
                    tr("Подключено", "Connected")
                } else when (snapshot.phase) {
                    ConnectionPhase.CONNECTED -> tr("Подключено", "Connected")
                    ConnectionPhase.CONNECTING -> tr("Ожидаем ответ VPN-сервера…", "Waiting for VPN server response…")
                    ConnectionPhase.WAITING -> tr("Ожидание сервера · VPN-туннель сохранён", "Waiting for server · VPN tunnel preserved")
                    ConnectionPhase.DISCONNECTING -> tr("Завершаем соединение…", "Disconnecting…")
                    ConnectionPhase.ERROR -> snapshot.error ?: tr("Ошибка соединения", "Connection error")
                    else -> tr("VPN отключён", "VPN disconnected")
                },
                color = if (snapshot.phase == ConnectionPhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            if (warning != null) Text(
                warning,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 12.dp, end = 12.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                active?.name ?: tr("Профиль не выбран", "No profile selected"),
                fontSize = 20.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
            )
            if (snapshot.phase == ConnectionPhase.WAITING && !confirmedBefore) Text(
                tr("Повторяем попытки автоматически. Для выхода нажмите «Отключить».", "Retrying automatically. Tap “Disconnect” to stop."),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (snapshot.tunnelActive) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(tr("Получено", "Received"), formatBytes(snapshot.rxBytes), Modifier.weight(1f))
                    StatCard(tr("Отправлено", "Sent"), formatBytes(snapshot.txBytes), Modifier.weight(1f))
                }
                val perSecUnit = tr("с", "s")
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.92f),
                    border = BorderStroke(1.dp, Color(0xFFE5DFD5)),
                    shadowElevation = 2.dp,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(
                        "↓ ${formatBytes(snapshot.rxBytesPerSecond)}/$perSecUnit   ·   ↑ ${formatBytes(snapshot.txBytesPerSecond)}/$perSecUnit",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        textAlign = TextAlign.Center,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        style = TextStyle(fontFeatureSettings = "tnum"),
                        color = Color(0xFF197B6E),
                    )
                }
            }
            Spacer(Modifier.height(profileGap))
            ProfileSwitcher(
                profiles = profiles,
                activeId = activeId,
                enabled = snapshot.phase !in setOf(ConnectionPhase.CONNECTING, ConnectionPhase.DISCONNECTING),
                actionsEnabled = snapshot.phase in setOf(ConnectionPhase.DISCONNECTED, ConnectionPhase.ERROR),
                modifier = Modifier.fillMaxWidth(),
                onSelect = { profile ->
                    activeId = profile.id
                    if (snapshot.tunnelActive) scope.launch {
                        app.tunnel.switchProfile(profile.id)
                            .onSuccess { reload(); snackbar.showSnackbar(tr(lang, "Профиль переключён: ${profile.name}", "Switched profile: ${profile.name}")) }
                            .onFailure { reload(); snackbar.showSnackbar(it.message ?: tr(lang, "Не удалось переключить профиль", "Failed to switch profile")) }
                    } else {
                        app.profiles.select(profile.id); reload()
                    }
                },
                onEdit = {
                    editing = it
                    dialogConfig = runCatching { app.profiles.readConfig(it.id) }.getOrDefault("")
                    showProfileDialog = true
                },
                onDelete = { deletingProfile = it },
            )
            Spacer(Modifier.height(20.dp))
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }

    deletingProfile?.let { target ->
        AlertDialog(
            onDismissRequest = { deletingProfile = null },
            title = { Text(tr("Удалить профиль «${target.name}»?", "Delete profile “${target.name}”?")) },
            text = { Text(tr("Зашифрованная конфигурация AWG будет удалена с устройства без возможности восстановления.", "The encrypted AWG configuration will be permanently deleted from this device.")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val deletedName = target.name
                        app.profiles.delete(target.id)
                        deletingProfile = null
                        reload()
                        scope.launch { snackbar.showSnackbar(tr(lang, "Профиль «$deletedName» удалён", "Profile “$deletedName” deleted")) }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(tr("Удалить профиль", "Delete profile")) }
            },
            dismissButton = {
                TextButton(onClick = { deletingProfile = null }) { Text(tr("Отмена", "Cancel")) }
            },
        )
    }

    if (imports.isNotEmpty()) AlertDialog(
        onDismissRequest = { if (!importBusy) imports = emptyList() },
        title = { Text(if (imports.size == 1) tr("Добавить профиль", "Add profile") else tr("Добавить ${imports.size} профилей", "Add ${imports.size} profiles")) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            if (imports.size == 1) {
                OutlinedTextField(importName, { importName = it.take(80) }, label = { Text(tr("Название", "Name")) }, singleLine = true)
                Text(tr("AWG ${imports.first().generation.label} · конфигурация проверена", "AWG ${imports.first().generation.label} · configuration verified"), Modifier.padding(top = 10.dp))
            } else imports.forEach { Text("${it.name} · AWG ${it.generation.label}", Modifier.padding(vertical = 4.dp)) }
            Text(tr("Ключи сохраняются зашифрованными на устройстве.", "Keys are stored encrypted on this device."), Modifier.padding(top = 10.dp), fontSize = 12.sp)
        } },
        confirmButton = { TextButton(enabled = !importBusy, onClick = {
            importBusy = true
            val candidates = if (imports.size == 1) listOf(imports.first().copy(name = importName)) else imports
            scope.launch {
                try {
                    check(!app.tunnel.state.value.tunnelActive) { tr(lang, "Сначала отключите VPN", "Disconnect VPN first") }
                    val created = withContext(Dispatchers.IO) { app.profiles.importValidated(candidates) }
                    imports = emptyList(); reload()
                    importBusy = false
                    snackbar.showSnackbar(tr(lang, "Добавлено профилей: ${created.size}", "Added profiles: ${created.size}"))
                } catch (error: Exception) {
                    EventLog.error("config.import.save", error); snackbar.showSnackbar("${error.javaClass.simpleName}: ${error.message}")
                } finally { importBusy = false }
            }
        }) { Text(if (importBusy) tr("Сохранение…", "Saving…") else tr("Добавить профиль", "Add profile")) } },
        dismissButton = { TextButton(enabled = !importBusy, onClick = { imports = emptyList() }) { Text(tr("Отмена", "Cancel")) } },
    )

    if (showProfileDialog) ProfileDialog(editing, dialogConfig, onDismiss = { showProfileDialog = false }) { name, config ->
        val wasEditing = editing != null
        runCatching {
            if (!wasEditing) app.profiles.create(name, config)
            else app.profiles.update(editing!!.copy(name = name), config)
        }.onSuccess {
            showProfileDialog = false; reload()
            scope.launch { snackbar.showSnackbar(if (wasEditing) tr(lang, "Изменения сохранены", "Changes saved") else tr(lang, "Профиль добавлен", "Profile added")) }
        }.onFailure { scope.launch { snackbar.showSnackbar(com.nexus.client.core.security.SecretMasker.mask(it.message ?: tr(lang, "Конфигурация некорректна", "Invalid configuration"))) } }
    }
}

@Composable
private fun AppUpdateBannerCard(
    state: AppUpdateState,
    onAction: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val info = state.info ?: return
    val phase = state.phase
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = Color(0xEBFFFFFF),
        border = BorderStroke(1.dp, Color(0x6614967B)),
        shadowElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    val title = when (phase) {
                        is AppUpdatePhase.Downloading -> tr("Скачивание Ex WG v${info.latestVersion}…", "Downloading Ex WG v${info.latestVersion}…")
                        is AppUpdatePhase.ReadyToInstall -> tr("Обновление v${info.latestVersion} скачано", "Update v${info.latestVersion} downloaded")
                        else -> tr("Доступна новая версия v${info.latestVersion}", "New version v${info.latestVersion} available")
                    }
                    Text(
                        text = title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF17181A),
                    )
                    val subtitle = when (phase) {
                        is AppUpdatePhase.Downloading -> {
                            val pct = phase.progressPercent
                            if (pct != null) tr("Прогресс: $pct% · проверка SHA-256", "Progress: $pct% · verifying SHA-256")
                            else tr("Загрузка пакета APK…", "Downloading APK package…")
                        }
                        is AppUpdatePhase.ReadyToInstall -> tr("SHA-256 проверен · нажмите «Установить»", "SHA-256 verified · tap “Install”")
                        is AppUpdatePhase.Error -> phase.message
                        else -> tr("Установка в 1 клик с сохранением ключей и правил", "1-click update keeping all keys and rules")
                    }
                    Text(
                        text = subtitle,
                        fontSize = 11.sp,
                        color = if (phase is AppUpdatePhase.Error) MaterialTheme.colorScheme.error else Color(0xFF6E6B65),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (phase !is AppUpdatePhase.Downloading) {
                        PrimaryPillButton(onClick = onAction) {
                            Text(
                                text = when (phase) {
                                    is AppUpdatePhase.ReadyToInstall -> tr("Установить", "Install")
                                    is AppUpdatePhase.Error -> tr("Повторить", "Retry")
                                    else -> tr("Обновить", "Update")
                                },
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                        }
                    }
                    SecondaryPillButton(onClick = onCancel) {
                        Text(tr("Отмена", "Cancel"), fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
            if (phase is AppUpdatePhase.Downloading) {
                val pct = phase.progressPercent
                if (pct != null) {
                    LinearProgressIndicator(
                        progress = { (pct / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(CircleShape),
                        color = Color(0xFF14967B),
                        trackColor = Color(0xFFE6E0D6),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(CircleShape),
                        color = Color(0xFF14967B),
                        trackColor = Color(0xFFE6E0D6),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileSwitcher(
    profiles: List<ProfileMeta>,
    activeId: String?,
    enabled: Boolean,
    actionsEnabled: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (ProfileMeta) -> Unit,
    onEdit: (ProfileMeta) -> Unit,
    onDelete: (ProfileMeta) -> Unit,
) {
    val activeIndex = profiles.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = activeIndex)

    LaunchedEffect(activeId, profiles.map { it.id }) {
        val alreadyVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == activeIndex }
        if (profiles.isNotEmpty() && !listState.isScrollInProgress && !alreadyVisible) {
            listState.animateScrollToItem(activeIndex)
        }
    }

    Box(modifier) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Профили", "Profiles"), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (profiles.isNotEmpty()) Text("  ${profiles.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.weight(1f))
                if (!enabled && profiles.size > 1) Text(tr("Переключение…", "Switching…"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            if (profiles.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(82.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xBFF3EFE8),
                    border = BorderStroke(1.dp, Color(0xFFD7CFC2)),
                ) {
                    Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        ExWgProfileGlyph(Modifier.size(30.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(tr("Профилей пока нет", "No profiles yet"), fontSize = 15.sp)
                            Text(tr("Нажмите + в правом верхнем углу, чтобы добавить конфигурацию", "Tap + in the top-right corner to add a configuration"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                val visibleCards = profiles.size.coerceIn(1, 3)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height((visibleCards * 88).dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    userScrollEnabled = enabled && profiles.size > 1,
                ) {
                    items(profiles, key = { profile -> profile.id }) { profile ->
                        val selected = profile.id == activeId
                        val cardInteraction = remember { MutableInteractionSource() }
                        val cardScale = rememberPressScale(cardInteraction, enabled && !selected, pressedScale = 0.98f)
                        val editDesc = tr("Изменить профиль ${profile.name}", "Edit profile ${profile.name}")
                        val deleteDesc = tr("Удалить профиль ${profile.name}", "Delete profile ${profile.name}")
                        Surface(
                            onClick = { if (enabled && !selected) onSelect(profile) },
                            enabled = enabled,
                            interactionSource = cardInteraction,
                            modifier = Modifier.fillMaxWidth().height(80.dp).graphicsLayer {
                                scaleX = cardScale
                                scaleY = cardScale
                            }.semantics {
                                role = Role.RadioButton
                                this.selected = selected
                                contentDescription = "${profile.name}, AWG ${profile.generation.label}"
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) Color.White else Color(0xBFF3EFE8),
                            border = BorderStroke(1.dp, if (selected) Color(0x59197B6E) else Color(0x80D7CFC2)),
                            shadowElevation = if (selected) 2.dp else 0.dp,
                        ) {
                            Row(Modifier.fillMaxSize().padding(start = 13.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = RoundedCornerShape(8.dp), color = if (selected) Color(0xFFDDF3EC) else Color(0xFFE6E0D5), modifier = Modifier.size(42.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        if (selected) SelectedProfileGlyph(Modifier.size(25.dp))
                                        else ExWgProfileGlyph(Modifier.size(25.dp))
                                    }
                                }
                                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                    Text(profile.name, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        if (selected) tr("Выбран · AWG ${profile.generation.label}", "Selected · AWG ${profile.generation.label}") else "AWG ${profile.generation.label}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(
                                    onClick = { onEdit(profile) },
                                    enabled = actionsEnabled,
                                    modifier = Modifier.size(48.dp).semantics { contentDescription = editDesc },
                                ) { ProfileActionGlyph(delete = false, modifier = Modifier.size(19.dp)) }
                                Spacer(Modifier.width(2.dp))
                                IconButton(
                                    onClick = { onDelete(profile) },
                                    enabled = actionsEnabled,
                                    modifier = Modifier.size(48.dp).semantics { contentDescription = deleteDesc },
                                ) { ProfileActionGlyph(delete = true, modifier = Modifier.size(19.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectedProfileGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 1.7.dp.toPx()
        drawCircle(Color(0xFF136952), radius = size.minDimension * .42f, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        val check = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * .28f, size.height * .51f)
            lineTo(size.width * .44f, size.height * .66f)
            lineTo(size.width * .73f, size.height * .35f)
        }
        drawPath(check, Color(0xFF136952), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}

@Composable
private fun ProfileActionGlyph(delete: Boolean, modifier: Modifier = Modifier) {
    val color = if (delete) MaterialTheme.colorScheme.error.copy(alpha = .9f) else MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        val stroke = 1.55.dp.toPx()
        if (delete) {
            drawLine(color, Offset(size.width * .22f, size.height * .3f), Offset(size.width * .78f, size.height * .3f), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color, Offset(size.width * .39f, size.height * .18f), Offset(size.width * .61f, size.height * .18f), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            val bin = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width * .29f, size.height * .36f)
                lineTo(size.width * .34f, size.height * .83f)
                lineTo(size.width * .66f, size.height * .83f)
                lineTo(size.width * .71f, size.height * .36f)
            }
            drawPath(bin, color, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            drawLine(color, Offset(size.width * .44f, size.height * .43f), Offset(size.width * .45f, size.height * .72f), stroke)
            drawLine(color, Offset(size.width * .56f, size.height * .43f), Offset(size.width * .55f, size.height * .72f), stroke)
        } else {
            val pencil = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width * .2f, size.height * .74f)
                lineTo(size.width * .28f, size.height * .48f)
                lineTo(size.width * .65f, size.height * .16f)
                lineTo(size.width * .83f, size.height * .35f)
                lineTo(size.width * .47f, size.height * .69f)
                close()
            }
            drawPath(pencil, color, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            drawLine(color, Offset(size.width * .2f, size.height * .74f), Offset(size.width * .16f, size.height * .86f), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

@Composable
private fun ConnectionButton(phase: ConnectionPhase, confirmedBefore: Boolean, enabled: Boolean, cancellable: Boolean, diameter: Dp, onClick: () -> Unit) {
    val motionEnabled = rememberMotionEnabled()
    val working = (phase == ConnectionPhase.CONNECTING && !confirmedBefore) || phase == ConnectionPhase.DISCONNECTING
    val pulse = if (working && motionEnabled) {
        val transition = rememberInfiniteTransition(label = "connectPulse")
        val value by transition.animateFloat(0.86f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
        value
    } else 1f
    val connected = phase == ConnectionPhase.CONNECTED || confirmedBefore
    val canClick = enabled && (!working || cancellable)
    val interactionSource = remember { MutableInteractionSource() }
    val pressScale = rememberPressScale(interactionSource, canClick, pressedScale = 0.96f)
    val buttonLabel = when {
        phase == ConnectionPhase.DISCONNECTING -> tr("Отключение…", "Disconnecting…")
        connected -> tr("Отключить", "Disconnect")
        phase == ConnectionPhase.CONNECTING && !cancellable -> tr("Подключение…", "Connecting…")
        phase == ConnectionPhase.CONNECTING -> tr("Отменить", "Cancel")
        phase == ConnectionPhase.WAITING -> tr("Отключить", "Disconnect")
        else -> tr("Подключить", "Connect")
    }
    Box(
        Modifier.size(diameter).alpha(if (enabled) pulse else .6f).graphicsLayer {
            scaleX = pressScale
            scaleY = pressScale
        },
        contentAlignment = Alignment.Center,
    ) {
        ConnectionHalo(Modifier.fillMaxSize(), phase = phase)
        val coreBrush = when {
            connected -> Brush.linearGradient(
                listOf(Color(0xFF2FA899), Color(0xFF197B6E), Color(0xFF115E54))
            )
            phase in setOf(ConnectionPhase.CONNECTING, ConnectionPhase.WAITING) -> Brush.linearGradient(
                listOf(Color(0xFFE5A93C), Color(0xFFC8881A))
            )
            else -> Brush.linearGradient(
                listOf(Color(0xFF26282B), Color(0xFF17181A))
            )
        }
        Surface(
            onClick = onClick,
            enabled = canClick,
            interactionSource = interactionSource,
            modifier = Modifier.size(diameter * .86f).semantics {
                role = Role.Button
                contentDescription = "$buttonLabel VPN"
            },
            shape = CircleShape,
            color = Color.Transparent,
        ) {
            Column(
                Modifier.fillMaxSize().background(coreBrush).padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (working) CircularProgressIndicator(Modifier.size(diameter * .22f), color = Color.White, strokeWidth = 2.dp)
                else ExWgPower(Modifier.size(diameter * .25f), color = Color.White)
                Spacer(Modifier.height(15.dp))
                Text(buttonLabel, color = Color.White, fontSize = if (diameter < 200.dp) 13.sp else 15.sp, fontWeight = FontWeight.Normal, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun ProfileDialog(meta: ProfileMeta?, initialConfig: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember(meta?.id) { mutableStateOf(meta?.name.orEmpty()) }
    var config by remember(initialConfig) { mutableStateOf(initialConfig) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (meta == null) tr("Добавить профиль", "Add profile") else tr("Изменить профиль", "Edit profile")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(tr("Название", "Name")) }, singleLine = true)
                OutlinedTextField(config, { config = it }, label = { Text(tr("AWG-конфигурация", "AWG configuration")) }, modifier = Modifier.fillMaxWidth().height(280.dp), maxLines = 14)
                Text(tr("После сохранения ключи будут скрыты и зашифрованы Android Keystore.", "After saving, keys will be hidden and encrypted with Android Keystore."), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, config) }) { Text(tr("Сохранить", "Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена", "Cancel")) } },
    )
}

@Composable
private fun RoutingScreen(app: NexusApplication) {
    val lang = LocalAppLanguage.current
    var settings by remember { mutableStateOf(app.profiles.routingSettings()) }
    var url by remember { mutableStateOf(settings.source) }
    var overrides by remember { mutableStateOf(settings.overrides) }
    var cached by remember { mutableStateOf(app.routing.cached()) }
    var rawProfileJson by remember { mutableStateOf(app.routing.rawProfileJson()) }
    var status by remember { mutableStateOf(settings.routingStatus) }
    var ruleAction by remember { mutableStateOf(OverrideAction.PROXY) }
    var ruleValue by remember { mutableStateOf("") }
    var actionMenu by remember { mutableStateOf(false) }
    var rawEditor by remember { mutableStateOf(false) }
    var showRawJson by remember { mutableStateOf(false) }
    var dnsExpanded by remember { mutableStateOf(false) }
    var ruleSearch by remember { mutableStateOf("") }
    var checkTarget by remember { mutableStateOf("") }
    var checkingRoute by remember { mutableStateOf(false) }
    var routeResult by remember { mutableStateOf<JSONObject?>(null) }
    val operation by app.routing.operation.collectAsState()
    val updating = operation?.busy == true
    val downloading = updating && operation?.downloading == true
    var confirmRebuild by remember { mutableStateOf(false) }
    var ruleToDelete by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf(runCatching { app.routing.summary(settings.overrides) }.getOrNull()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val latest = app.profiles.routingSettings()
        settings = latest
        if (cached == null) cached = withContext(Dispatchers.IO) { app.routing.cached() }
        if (rawProfileJson.isBlank()) rawProfileJson = withContext(Dispatchers.IO) { app.routing.rawProfileJson() }
        if (summary == null) summary = withContext(Dispatchers.IO) { runCatching { app.routing.summary(latest.overrides) }.getOrNull() }
    }

    LaunchedEffect(operation) {
        val currentOp = operation
        if (currentOp != null) {
            status = currentOp.message
            if (!currentOp.busy) {
                val latest = app.profiles.routingSettings()
                settings = latest
                url = latest.source
                overrides = latest.overrides
                cached = withContext(Dispatchers.IO) { app.routing.cached() }
                rawProfileJson = withContext(Dispatchers.IO) { app.routing.rawProfileJson() }
                summary = withContext(Dispatchers.IO) { runCatching { app.routing.summary(latest.overrides) }.getOrNull() }
            }
        }
    }

    fun applyOverridesImmediately(updatedOverrides: String, message: String) {
        overrides = updatedOverrides
        status = message
        app.routing.startUpdate(source = url, overrides = updatedOverrides, download = false)
    }

    if (confirmRebuild) AlertDialog(
        onDismissRequest = { confirmRebuild = false },
        title = { Text(tr("Восстановить правила с нуля?", "Reset rules to defaults?")) },
        text = {
            Text(
                tr(
                    "Сбросим адрес источника на https://routing.help/, очистим локальные правила (включая резервный DNS) и заново скачаем профиль маршрутизации и обе геобазы. Ключи VPN останутся. При сетевой ошибке прежние правила сохранятся.",
                    "This will reset the source URL to https://routing.help/, clear local rules (including fallback DNS), and re-download the routing profile and both geo databases. VPN keys are kept. On network error, previous rules are preserved.",
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                confirmRebuild = false
                url = DEFAULT_SOURCE
                overrides = ""
                app.routing.startUpdate(source = DEFAULT_SOURCE, overrides = "", download = true, rebuild = true)
            }) { Text(tr("Восстановить", "Reset")) }
        },
        dismissButton = { TextButton(onClick = { confirmRebuild = false }) { Text(tr("Отмена", "Cancel")) } },
    )

    ruleToDelete?.let { line ->
        AlertDialog(
            onDismissRequest = { ruleToDelete = null },
            title = { Text(tr("Удалить локальное правило?", "Delete local rule?")) },
            text = { Text(tr("Правило «$line» будет удалено из локальных исключений.", "Rule \"$line\" will be removed from local overrides.")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val updated = overrides.lines().toMutableList().also { it.remove(line) }.filter(String::isNotBlank).joinToString("\n")
                        ruleToDelete = null
                        applyOverridesImmediately(updated, if (lang == AppLanguage.EN) "Local rule removed." else "Локальное правило удалено.")
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(tr("Удалить", "Delete")) }
            },
            dismissButton = {
                TextButton(onClick = { ruleToDelete = null }) { Text(tr("Отмена", "Cancel")) }
            },
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Card 1 — Источник правил
        GlassCard {
            Text(tr("Источник правил", "Rules source"), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(
                tr(
                    "Глобальный профиль маршрутизации и геобаз для всех конфигураций VPN.",
                    "Global routing profile and geo databases shared across all VPN configurations.",
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(tr("Источник правил маршрутизации (HTTPS, happ://, JSON или правила)", "Routing rules source (HTTPS, happ://, JSON, or rules)")) },
                supportingText = { Text(tr("Можно вставить ссылку, happ://routing/onadd/..., JSON или список правил.", "Paste a URL, happ://routing/onadd/..., raw JSON, or rule lines.")) },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 4,
                enabled = !downloading,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryPillButton(
                    onClick = { app.routing.startUpdate(source = url, overrides = overrides, download = false) },
                    enabled = !updating,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(tr("Сохранить источник", "Save source"), maxLines = 1)
                }
                PrimaryPillButton(
                    onClick = { app.routing.startUpdate(source = url, overrides = overrides, download = true) },
                    enabled = !updating,
                    modifier = Modifier.weight(1f),
                ) {
                    if (downloading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    else Text(tr("Обновить правила", "Update rules"), maxLines = 1)
                }
            }
            if (status.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            cached?.let { profile ->
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xBFF3EFE8),
                    border = BorderStroke(1.dp, Color(0xFFD7CFC2)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(tr("Параметры профиля", "Profile parameters"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        DetailLine(tr("Название", "Name"), profile.name)
                        DetailLine(tr("Порядок проверки", "Evaluation order"), formatRuleOrder(profile, lang))
                        DetailLine(tr("Проверка доменов", "Domain strategy"), formatDomainStrategy(profile.domainStrategy, lang))
                        DetailLine(tr("Обновлено", "Updated"), formatEpochMillis(settings.lastRoutingUpdate, lang))
                        DetailLine(tr("Редакция базы", "Database revision"), formatProfileTimestamp(profile.lastUpdated, lang))
                    }
                }
                if (profile.useChunkFiles) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFFFF7E6),
                        border = BorderStroke(1.dp, Color(0xFFFFCA78)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(tr("Неподдерживаемые поля профиля", "Unsupported profile fields"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF9C6500))
                            Text(tr("Поле UseChunkFiles=true проигнорировано.", "Field UseChunkFiles=true was ignored."), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // Card 2 — Проверить маршрут
        GlassCard {
            Text(tr("Проверить маршрут", "Check route"), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(
                tr(
                    "Покажет, какое правило сработало для домена или IP-адреса и через какой маршрут пойдёт трафик.",
                    "Shows which rule matched a domain or IP address and which path the traffic will take.",
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = checkTarget,
                    onValueChange = { checkTarget = it },
                    label = { Text(tr("sberbank.ru, youtube.com или 1.1.1.1", "sberbank.ru, youtube.com, or 1.1.1.1")) },
                    singleLine = true,
                    enabled = !checkingRoute,
                    modifier = Modifier.weight(1f),
                )
                PrimaryPillButton(
                    onClick = {
                        val target = checkTarget.trim()
                        if (target.isEmpty()) return@PrimaryPillButton
                        checkingRoute = true
                        scope.launch {
                            try {
                                routeResult = app.tunnel.checkRoute(target)
                            } finally {
                                checkingRoute = false
                            }
                        }
                    },
                    enabled = !checkingRoute && checkTarget.isNotBlank(),
                    containerColor = Color(0xFF197B6E),
                ) {
                    if (checkingRoute) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    else Text(tr("Проверить", "Check"))
                }
            }
            routeResult?.let { res ->
                Spacer(Modifier.height(10.dp))
                val route = res.optString("action", "").ifBlank { res.optString("route", "") }.lowercase()
                val resultBg = when (route) {
                    "direct", "proxy" -> Color(0xFFDDF3EC)
                    "block" -> Color(0xFFFDE8E8)
                    else -> Color(0xBFF3EFE8)
                }
                val resultBorder = when (route) {
                    "direct", "proxy" -> Color(0x59197B6E)
                    "block" -> Color(0x59B83B2A)
                    else -> Color(0xFFD7CFC2)
                }
                val routeColor = when (route) {
                    "direct", "proxy" -> Color(0xFF136952)
                    "block" -> Color(0xFFB83B2A)
                    else -> Color(0xFF17181A)
                }
                val routeTitle = when (route) {
                    "direct" -> tr("Direct — напрямую в обход VPN", "Direct — bypasses VPN")
                    "proxy" -> tr("Proxy — через туннель Ex WG", "Proxy — routed via Ex WG tunnel")
                    "block" -> tr("Block — соединение заблокировано", "Block — connection blocked")
                    else -> res.optString("error", tr("Не удалось определить маршрут", "Could not determine route"))
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = resultBg,
                    border = BorderStroke(1.dp, resultBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(routeTitle, color = routeColor, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        val matchedRule = res.optString("rule", "")
                        if (matchedRule.isNotBlank()) {
                            DetailLine(tr("Правило", "Rule"), matchedRule)
                        }
                        val addresses = res.optJSONArray("addresses")
                        if (addresses != null && addresses.length() > 0) {
                            val ipList = (0 until addresses.length()).mapNotNull { idx ->
                                addresses.optString(idx).takeIf(String::isNotBlank)
                                    ?: addresses.optJSONObject(idx)?.optString("ip")?.takeIf(String::isNotBlank)
                            }
                            if (ipList.isNotEmpty()) {
                                DetailLine(tr("IP-адреса", "IP addresses"), ipList.joinToString(", "))
                            }
                        }
                        val paths = res.optJSONArray("paths")
                        if (paths != null && paths.length() > 0) {
                            val pathList = (0 until paths.length()).mapNotNull { idx ->
                                paths.optString(idx).takeIf(String::isNotBlank)
                            }.map { if (it.equals("exwg", ignoreCase = true)) "Ex WG" else it }
                            if (pathList.isNotEmpty()) {
                                DetailLine(tr("Путь", "Path"), pathList.joinToString(", "))
                            }
                        }
                        val note = res.optString("note", "")
                        if (note.isNotBlank()) {
                            Text(note, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val dnsErr = res.optString("dnsError", "")
                        if (dnsErr.isNotBlank()) {
                            Text("DNS: $dnsErr", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                        }
                        val err = res.optString("error", "")
                        if (err.isNotBlank() && route.isNotEmpty()) {
                            Text(err, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        // Card 3 — Состав routing-пакета
        cached?.let { profile ->
            GlassCard {
                Text(tr("Состав routing-пакета", "Routing bundle contents"), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))

                // Expandable DNS Summary
                val directServer = profile.domesticDns.orEmpty()
                val proxyServer = profile.remoteDns.orEmpty()
                val directProto = formatDnsProtocol(profile.domesticDnsType, directServer, profile.domesticDnsDomain)
                val proxyProto = formatDnsProtocol(profile.remoteDnsType, proxyServer, profile.remoteDnsDomain)
                Surface(
                    onClick = { dnsExpanded = !dnsExpanded },
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xBFF3EFE8),
                    border = BorderStroke(1.dp, Color(0xFFD7CFC2)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("DNS", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(
                                    "Direct: $directProto · Proxy: $proxyProto",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                if (dnsExpanded) tr("Свернуть ▴", "Collapse ▴") else tr("Развернуть ▾", "Expand ▾"),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (dnsExpanded) {
                            HorizontalDivider(color = ExWgOutline.copy(alpha = .5f), thickness = .6.dp)
                            DnsEndpointCard(
                                title = tr("Direct DNS (прямые сайты)", "Direct DNS (direct sites)"),
                                protocol = directProto,
                                server = directServer,
                                domain = profile.domesticDnsDomain,
                                ip = profile.domesticDnsIp,
                            )
                            DnsEndpointCard(
                                title = tr("Proxy DNS (через туннель)", "Proxy DNS (via tunnel)"),
                                protocol = proxyProto,
                                server = proxyServer,
                                domain = profile.remoteDnsDomain,
                                ip = profile.remoteDnsIp,
                            )
                            DetailLine("FakeDNS", if (profile.fakeDns) tr("Включён", "Enabled") else tr("Выключен", "Disabled"))
                            if (profile.dnsHosts.isNotEmpty()) {
                                Text(tr("Статические записи DnsHosts:", "Static DnsHosts entries:"), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                profile.dnsHosts.entries.forEach { (host, ip) ->
                                    DetailLine(host, ip)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                val builtInLabel = tr("Встроенная", "Built-in")
                val geoSiteSize = summary?.takeIf { it.has("geosite.dat.bytes") }?.let { formatBytes(it.optLong("geosite.dat.bytes")) } ?: builtInLabel
                val geoIpSize = summary?.takeIf { it.has("geoip.dat.bytes") }?.let { formatBytes(it.optLong("geoip.dat.bytes")) } ?: builtInLabel
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard("GeoSite · ${extractHostLabel(profile.geositeUrl, lang)}", geoSiteSize, Modifier.weight(1f))
                    StatCard("GeoIP · ${extractHostLabel(profile.geoipUrl, lang)}", geoIpSize, Modifier.weight(1f))
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = ruleSearch,
                    onValueChange = { ruleSearch = it },
                    label = { Text(tr("Найти правило (домен, geosite, geoip, IP)", "Find rule (domain, geosite, geoip, IP)")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(10.dp))
                val expandedCounts = summary?.optJSONObject("expandedRules")
                EffectiveCategorySection(
                    category = OverrideAction.DIRECT,
                    accent = NexusMint,
                    items = effectiveCategoryRules(OverrideAction.DIRECT, profile, overrides, ruleSearch),
                    expandedInfo = expandedCounts?.optJSONObject("direct"),
                    forceExpanded = ruleSearch.isNotBlank(),
                    enabled = !updating,
                    onAssign = { rule, targetAction ->
                        val updated = OverrideParser.assignRule(overrides, rule, targetAction)
                        val label = targetAction?.name ?: if (lang == AppLanguage.EN) "Profile default" else "По профилю"
                        applyOverridesImmediately(updated, if (lang == AppLanguage.EN) "Route for $rule changed ($label)." else "Маршрут для $rule изменён ($label).")
                    },
                )
                Spacer(Modifier.height(8.dp))
                EffectiveCategorySection(
                    category = OverrideAction.PROXY,
                    accent = MaterialTheme.colorScheme.tertiary,
                    items = effectiveCategoryRules(OverrideAction.PROXY, profile, overrides, ruleSearch),
                    expandedInfo = expandedCounts?.optJSONObject("proxy"),
                    forceExpanded = ruleSearch.isNotBlank(),
                    enabled = !updating,
                    onAssign = { rule, targetAction ->
                        val updated = OverrideParser.assignRule(overrides, rule, targetAction)
                        val label = targetAction?.name ?: if (lang == AppLanguage.EN) "Profile default" else "По профилю"
                        applyOverridesImmediately(updated, if (lang == AppLanguage.EN) "Route for $rule changed ($label)." else "Маршрут для $rule изменён ($label).")
                    },
                )
                Spacer(Modifier.height(8.dp))
                EffectiveCategorySection(
                    category = OverrideAction.BLOCK,
                    accent = MaterialTheme.colorScheme.error,
                    items = effectiveCategoryRules(OverrideAction.BLOCK, profile, overrides, ruleSearch),
                    expandedInfo = expandedCounts?.optJSONObject("block"),
                    forceExpanded = ruleSearch.isNotBlank(),
                    enabled = !updating,
                    onAssign = { rule, targetAction ->
                        val updated = OverrideParser.assignRule(overrides, rule, targetAction)
                        val label = targetAction?.name ?: if (lang == AppLanguage.EN) "Profile default" else "По профилю"
                        applyOverridesImmediately(updated, if (lang == AppLanguage.EN) "Route for $rule changed ($label)." else "Маршрут для $rule изменён ($label).")
                    },
                )
            }
        } ?: GlassCard {
            Text(tr("Исправный кэш отсутствует. При сетевой ошибке последняя проверенная версия сохраняется.", "No valid routing cache found. Last verified rules are preserved on network error."))
        }

        // Card 4 — Локальные правила для всех конфигураций
        GlassCard {
            Text(tr("Локальные правила для всех конфигураций", "Local rules for all configurations"), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(
                tr(
                    "Применяются поверх любого профиля правил и сохраняются при обновлении списков.",
                    "Applied on top of any routing profile and preserved across rule updates.",
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    TextButton(onClick = { actionMenu = true }, enabled = !updating) {
                        Text(tr("Маршрут: ${ruleAction.title} ▾", "Action: ${ruleAction.title} ▾"))
                    }
                    DropdownMenu(expanded = actionMenu, onDismissRequest = { actionMenu = false }) {
                        OverrideAction.entries.forEach { choice ->
                            DropdownMenuItem(
                                text = { Text(choice.title) },
                                onClick = { ruleAction = choice; actionMenu = false },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = ruleValue,
                    onValueChange = { ruleValue = it },
                    label = { Text(tr("geosite:apple, geoip:private или apple.com", "geosite:apple, geoip:private, or apple.com")) },
                    singleLine = true,
                    enabled = !updating,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PrimaryPillButton(
                    enabled = !updating && ruleValue.isNotBlank(),
                    onClick = {
                        runCatching {
                            val normalized = OverrideParser.normalizeRuleTarget(ruleValue)
                            require(normalized.isNotEmpty()) { if (lang == AppLanguage.EN) "Specify a rule" else "Укажите правило" }
                            OverrideParser.assignRule(overrides, normalized, ruleAction.routeAction) to normalized
                        }.onSuccess { (updated, normalized) ->
                            ruleValue = ""
                            applyOverridesImmediately(
                                updated,
                                if (lang == AppLanguage.EN) "Added local rule: ${ruleAction.title} $normalized" else "Добавлено локальное правило: ${ruleAction.title} $normalized",
                            )
                        }.onFailure {
                            status = it.message ?: if (lang == AppLanguage.EN) "Invalid rule" else "Некорректное правило"
                        }
                    },
                ) {
                    Text(tr("Добавить правило", "Add rule"))
                }
            }

            val localLines = overrides.lines().filter { line ->
                val trimmed = line.trim().lowercase()
                trimmed.isNotEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("dns-")
            }
            if (localLines.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                localLines.forEach { line ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = Color.White,
                        border = BorderStroke(1.dp, Color(0xFFE5DFD5)),
                    ) {
                        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(line, modifier = Modifier.weight(1f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            TextButton(
                                onClick = { ruleToDelete = line },
                                enabled = !updating,
                            ) {
                                Text(tr("Удалить", "Delete"), fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Резервный DNS Яндекса", "Yandex fallback DNS"), fontSize = 14.sp)
                    Text(
                        tr("Используется только при сбое основного DNS.", "Used only when the primary DNS fails."),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = isFallbackDnsEnabled(overrides),
                    enabled = !updating,
                    onCheckedChange = { enabled ->
                        val changed = setFallbackDns(overrides, enabled)
                        val msg = if (lang == AppLanguage.EN) {
                            if (enabled) "Fallback DNS enabled." else "Fallback DNS disabled."
                        } else {
                            if (enabled) "Резервный DNS включён." else "Резервный DNS выключен."
                        }
                        applyOverridesImmediately(changed, msg)
                    },
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryPillButton(onClick = { rawEditor = !rawEditor }, modifier = Modifier.weight(1f)) {
                    Text(if (rawEditor) tr("Скрыть редактор", "Hide editor") else tr("Исходный редактор", "Raw editor"), maxLines = 1, fontSize = 12.sp)
                }
                SecondaryPillButton(onClick = { showRawJson = !showRawJson }, modifier = Modifier.weight(1f)) {
                    Text(if (showRawJson) tr("Скрыть JSON", "Hide JSON") else tr("Показать JSON", "Show JSON"), maxLines = 1, fontSize = 12.sp)
                }
            }
            if (rawEditor) {
                OutlinedTextField(
                    value = rulesOnly(overrides),
                    onValueChange = { overrides = replaceRulesOnly(overrides, it) },
                    label = { Text(tr("Локальные правила", "Local rules")) },
                    supportingText = { Text(tr("Например: direct domain:example.ru", "Example: direct domain:example.ru")) },
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                    enabled = !updating,
                )
            }
            if (showRawJson && rawProfileJson.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xBFF3EFE8),
                    border = BorderStroke(1.dp, Color(0xFFD7CFC2)),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                ) {
                    Text(
                        rawProfileJson,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryPillButton(
                    onClick = { confirmRebuild = true },
                    enabled = !updating,
                    modifier = Modifier.weight(1f),
                    contentColor = MaterialTheme.colorScheme.error,
                ) {
                    Text(tr("Восстановить с нуля", "Reset to defaults"), maxLines = 1)
                }
                PrimaryPillButton(
                    onClick = { app.routing.startUpdate(source = url, overrides = overrides, download = false) },
                    enabled = !updating,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(tr("Применить", "Apply"), maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(70.dp))
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(132.dp))
        Text(value.ifBlank { "—" }, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun DnsEndpointCard(title: String, protocol: String, server: String, domain: String, ip: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xBFF3EFE8),
        border = BorderStroke(1.dp, Color(0xFFD7CFC2)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            DetailLine(tr("Протокол", "Protocol"), protocol)
            DetailLine(tr("Сервер", "Server"), server.ifBlank { domain })
            if (domain.isNotBlank() && domain != server) {
                DetailLine(tr("Домен / SNI", "Domain / SNI"), domain)
            }
            DetailLine("Bootstrap IP", ip.ifBlank { tr("Авто", "Auto") })
        }
    }
}

@Composable
private fun EffectiveCategorySection(
    category: OverrideAction,
    accent: Color,
    items: List<EffectiveRuleItem>,
    expandedInfo: JSONObject?,
    forceExpanded: Boolean,
    enabled: Boolean,
    onAssign: (String, RouteAction?) -> Unit,
) {
    val lang = LocalAppLanguage.current
    var userExpanded by remember { mutableStateOf(false) }
    val expanded = userExpanded || forceExpanded
    val overriddenCount = items.count { it.overridden }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xBFF3EFE8),
        border = BorderStroke(1.dp, Color(0xFFD7CFC2)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${category.title} · ${categoryCountLabel(items.size, lang)}", color = accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        if (overriddenCount > 0) {
                            Text(tr("изменено: $overriddenCount", "overridden: $overriddenCount"), fontSize = 11.sp, color = Color(0xFFD97706))
                        }
                    }
                    expandedInfo?.let { counts ->
                        Text(
                            tr(
                                "Развёрнуто: ${counts.optInt("domains")} доменов · ${counts.optInt("ipPrefixes")} IP-сетей",
                                "Expanded: ${counts.optInt("domains")} domains · ${counts.optInt("ipPrefixes")} IP prefixes",
                            ),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (items.isNotEmpty()) {
                    TextButton(onClick = { userExpanded = !userExpanded }) {
                        Text(if (expanded) tr("Свернуть ▴", "Collapse ▴") else tr("Развернуть (${items.size}) ▾", "Expand (${items.size}) ▾"), fontSize = 12.sp)
                    }
                }
            }
            if (!expanded) {
                val preview = items.take(5).joinToString(", ") { it.value }
                Text(
                    preview.ifBlank { tr("Правил нет", "No rules") },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                Spacer(Modifier.height(6.dp))
                val visibleItems = items.take(150)
                visibleItems.forEach { item ->
                    var menuOpen by remember(item.value) { mutableStateOf(false) }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.White,
                        border = BorderStroke(1.dp, if (item.overridden) Color(0xFFFFCA78) else Color(0xFFE5DFD5)),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    ) {
                        Row(Modifier.padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.value, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (item.overridden) {
                                    val badge = if (item.originAction != null) {
                                        tr("изменено (в профиле: ${item.originAction.title})", "overridden (profile: ${item.originAction.title})")
                                    } else {
                                        tr("локальное правило", "local rule")
                                    }
                                    Text(badge, fontSize = 10.sp, color = Color(0xFFD97706))
                                }
                            }
                            Box {
                                TextButton(onClick = { menuOpen = true }, enabled = enabled) {
                                    Text("${item.effectiveAction.title} ▾", fontSize = 11.sp, color = accent)
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    if (item.originAction != null) {
                                        DropdownMenuItem(
                                            text = { Text(tr("По профилю (${item.originAction.title})", "Profile default (${item.originAction.title})")) },
                                            onClick = { menuOpen = false; onAssign(item.value, null) },
                                        )
                                    } else {
                                        DropdownMenuItem(
                                            text = { Text(tr("Удалить локальное правило", "Delete local rule")) },
                                            onClick = { menuOpen = false; onAssign(item.value, null) },
                                        )
                                    }
                                    OverrideAction.entries.forEach { dest ->
                                        DropdownMenuItem(
                                            text = { Text(dest.title) },
                                            onClick = {
                                                menuOpen = false
                                                val targetAction = if (item.originAction == dest) null else dest.routeAction
                                                onAssign(item.value, targetAction)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (items.size > visibleItems.size) {
                    Text(
                        tr(
                            "Показаны первые ${visibleItems.size} из ${items.size}. Используйте поиск выше для фильтрации.",
                            "Showing first ${visibleItems.size} of ${items.size}. Use the search box above to filter.",
                        ),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsScreen(app: NexusApplication, onHideDiagnostics: () -> Unit) {
    val lang = LocalAppLanguage.current
    val active = app.profiles.active()
    val snapshot by app.tunnel.state.collectAsState()
    val logs by EventLog.entries.collectAsState()
    var report by remember { mutableStateOf<DiagnosticReport?>(null) }
    var running by remember { mutableStateOf(false) }
    var extraDomain by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var batteryUnrestricted by remember { mutableStateOf(DeviceStabilitySettings.ignoresBatteryOptimizations(context)) }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryUnrestricted = DeviceStabilitySettings.ignoresBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val clipboard = LocalClipboardManager.current
    var exportText by remember { mutableStateOf<String?>(null) }
    var historyDays by remember { mutableStateOf<Int?>(null) }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val text = exportText; exportText = null
        if (uri != null && text != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) {
                        if (lang == AppLanguage.EN) "Failed to open report file" else "Не удалось открыть файл отчёта"
                    }.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                }
                android.widget.Toast.makeText(
                    context,
                    if (lang == AppLanguage.EN) "Report saved" else "Отчёт сохранён",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            } catch (error: Exception) {
                EventLog.error("diagnostics.export", error)
                android.widget.Toast.makeText(context, "${error.javaClass.simpleName}: ${error.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    val historyExportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val days = historyDays
        historyDays = null
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) {
                        if (lang == AppLanguage.EN) "Failed to open log file" else "Не удалось открыть файл журнала"
                    }.buffered().use { output -> EventLog.writeHistory(output, days) }
                }
                android.widget.Toast.makeText(
                    context,
                    if (lang == AppLanguage.EN) "Diagnostics history saved" else "История диагностики сохранена",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            } catch (error: Exception) {
                EventLog.error("diagnostics.history.export", error)
                android.widget.Toast.makeText(context, "${error.javaClass.simpleName}: ${error.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    fun saveReport(text: String) {
        exportText = text
        exportFile.launch("Ex-WG-diagnostics-${System.currentTimeMillis()}.txt")
    }
    fun copyReport(text: String) {
        // Clipboard is a Binder transaction; a large UTF-16 report can exceed
        // Android's shared transaction buffer. Export it intact, not truncated.
        if (text.length > 96 * 1024) { saveReport(text); return }
        try { clipboard.setText(AnnotatedString(text)) }
        catch (error: Exception) { EventLog.error("diagnostics.clipboard", error); saveReport(text) }
    }
    fun saveHistory(days: Int?) {
        historyDays = days
        val period = when (days) { 1 -> "today"; 7 -> "7-days"; else -> "all" }
        historyExportFile.launch("Ex-WG-diagnostics-history-$period-${System.currentTimeMillis()}.txt")
    }
    val history = remember(logs) { EventLog.historySummary() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Проверка соединения", "Connection check"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            SecondaryPillButton(onClick = onHideDiagnostics, enabled = !running) {
                Text(tr("Скрыть", "Hide"), maxLines = 1, fontSize = 12.sp)
            }
            PrimaryPillButton(onClick = {
                running = true
                scope.launch {
                    try {
                        val raw = withContext(Dispatchers.IO) { active?.let { app.profiles.readConfig(it.id) } }
                        val routingSettings = app.profiles.routingSettings()
                        report = DiagnosticsRunner.run(context, raw, snapshot, app.routing.cached(), routingSettings.overrides, extraDomain)
                        EventLog.add("Диагностика завершена")
                    } catch (error: Exception) { EventLog.error("diagnostics.run", error) }
                    finally { running = false }
                }
            }, enabled = !running) {
                if (running) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                else Text(tr("Проверить", "Run check"))
            }
        }
        OutlinedTextField(
            extraDomain,
            { extraDomain = it },
            label = { Text(tr("Проверить свой домен (необязательно)", "Check custom domain (optional)")) },
            supportingText = { Text(tr("Например: vk.com. Проверяется DNS и HTTPS, не весь функционал приложения.", "Example: vk.com. Tests DNS and HTTPS reachability.")) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !running,
        )
        GlassCard {
            Text(tr("Установка и данные", "Installation & storage"), fontWeight = FontWeight.SemiBold)
            Text(app.currentStorageSummary(), fontSize = 12.sp)
            Text(
                tr(
                    "Обновление APK сохраняет профили. Чтобы удалить всё, включая ключи VPN: откройте сведения о приложении → Хранилище → Очистить данные. Перед этим сохраните исходные конфигурации.",
                    "APK updates preserve your profiles. To wipe everything including VPN keys: open App Info → Storage → Clear data. Back up your configurations first.",
                ),
                fontSize = 12.sp,
            )
            SecondaryPillButton(onClick = { context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))) }) {
                Text(tr("Сведения о приложении", "App info"), maxLines = 1)
            }
        }
        GlassCard {
            Text(tr("Стабильная работа в фоне", "Background reliability"), fontWeight = FontWeight.SemiBold)
            Text(
                if (batteryUnrestricted) tr("Системное исключение из экономии энергии включено.", "Unrestricted battery usage is enabled.")
                else tr("Система может ограничить VPN при выключенном экране. Разрешите Ex WG работать без ограничений.", "The system may restrict the VPN when the screen is off. Allow unrestricted battery usage for Ex WG."),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(DeviceStabilitySettings.manufacturerGuidance(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                tr(
                    "Ex WG не меняет эти параметры самостоятельно: окончательное подтверждение всегда выполняется в системном интерфейсе.",
                    "Ex WG never changes system settings silently: final confirmation always happens in the Android system dialog.",
                ),
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!batteryUnrestricted) {
                    PrimaryPillButton(
                        onClick = {
                            if (!DeviceStabilitySettings.openBatteryExemption(context)) {
                                EventLog.add("battery.settings unavailable manufacturer=${android.os.Build.MANUFACTURER}")
                            }
                        },
                        containerColor = Color(0xFF197B6E),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(tr("Разрешить работу", "Allow unrestricted"), maxLines = 1, fontSize = 12.sp)
                    }
                }
                SecondaryPillButton(
                    onClick = { DeviceStabilitySettings.openApplicationDetails(context) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(tr("Открыть настройки Ex WG", "Open Ex WG settings"), maxLines = 1, fontSize = 12.sp)
                }
            }
        }
        report?.let { value ->
            value.checks.forEach { check ->
                GlassCard {
                    val color = when (check.level) { CheckLevel.PASS -> NexusMint; CheckLevel.WARN -> Color(0xFFD97706); CheckLevel.FAIL -> MaterialTheme.colorScheme.error }
                    Text(check.name, color = color, fontWeight = FontWeight.SemiBold)
                    Text(check.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            GlassCard {
                Text(tr("Вероятная причина", "Likely cause"), fontWeight = FontWeight.SemiBold)
                Text(value.reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryPillButton(onClick = { copyReport(value.fullText()) }, modifier = Modifier.weight(1f)) {
                        Text(tr("Копировать", "Copy"), maxLines = 1)
                    }
                    SecondaryPillButton(onClick = { saveReport(value.fullText()) }, modifier = Modifier.weight(1f)) {
                        Text(tr("Сохранить .txt", "Save .txt"), maxLines = 1)
                    }
                }
            }
        } ?: GlassCard {
            Text(
                tr(
                    "Проверка доступна и при ожидании handshake. Полный журнал содержит исходные ошибки, коды, адреса и сведения об устройстве. Автоматически никуда не отправляется.",
                    "Checks can run even while waiting for a handshake. The full log includes raw errors, codes, addresses, and device info, and is never sent anywhere automatically.",
                ),
            )
        }
        Text(tr("Журнал событий", "Event log"), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Text(
            tr(
                "Суточная история: ${history.days} дн., ${history.files} файлов, ${formatBytes(history.bytesOnDisk)} на устройстве. Хранится до 14 суток или 64 МБ; закрытые части сжимаются без потерь.",
                "Rolling history: ${history.days} days, ${history.files} files, ${formatBytes(history.bytesOnDisk)} on device. Kept up to 14 days or 64 MB; rotated segments are losslessly compressed.",
            ),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        history.lastStorageError?.let { error ->
            Text(tr("Ошибка хранения журнала: $error", "Log storage error: $error"), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryPillButton(onClick = { copyReport(EventLog.export()) }, modifier = Modifier.weight(1f)) {
                Text(tr("Копировать журнал", "Copy log"), maxLines = 1, fontSize = 12.sp)
            }
            SecondaryPillButton(onClick = { saveReport(EventLog.export()) }, modifier = Modifier.weight(1f)) {
                Text(tr("Сохранить .txt", "Save .txt"), maxLines = 1, fontSize = 12.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SecondaryPillButton(onClick = { saveHistory(1) }, modifier = Modifier.weight(1f)) {
                Text(tr("Сегодня", "Today"), maxLines = 1, fontSize = 11.sp)
            }
            SecondaryPillButton(onClick = { saveHistory(7) }, modifier = Modifier.weight(1f)) {
                Text(tr("7 дней", "7 days"), maxLines = 1, fontSize = 11.sp)
            }
            SecondaryPillButton(onClick = { saveHistory(null) }, modifier = Modifier.weight(1f)) {
                Text(tr("Вся история", "All history"), maxLines = 1, fontSize = 11.sp)
            }
        }
        GlassCard {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xBFF3EFE8),
                border = BorderStroke(1.dp, Color(0xFFD7CFC2)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    logs.takeLast(40).asReversed().joinToString("\n").ifBlank { tr("Событий пока нет", "No events yet") },
                    fontSize = 11.sp,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                    color = Color(0xFF5C5852),
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
        Spacer(Modifier.height(70.dp))
    }
}

@Composable
private fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xDBFAF8F4),
        border = BorderStroke(1.dp, Color(0xEBFFFFFF)),
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(18.dp), content = content)
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = Color(0xBFF3EFE8),
        border = BorderStroke(1.dp, Color(0xFFD7CFC2)),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                style = TextStyle(fontFeatureSettings = "tnum"),
                color = Color(0xFF17181A),
            )
        }
    }
}

@Composable
private fun SecondaryPillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = Color(0xFF17181A),
    content: @Composable () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = CircleShape,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.White,
            contentColor = contentColor,
            disabledContainerColor = Color.White.copy(alpha = 0.5f),
            disabledContentColor = Color(0xFF6E6B65).copy(alpha = 0.5f),
        ),
        border = BorderStroke(1.dp, Color(0xFFD7CFC2)),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
    ) {
        content()
    }
}

@Composable
private fun PrimaryPillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = Color(0xFF18191B),
    contentColor: Color = Color.White,
    content: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor.copy(alpha = 0.4f),
            disabledContentColor = contentColor.copy(alpha = 0.5f),
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
    ) {
        content()
    }
}
