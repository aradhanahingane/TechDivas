package com.techdivas.stressanalyzer

import android.os.Bundle
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.techdivas.stressanalyzer.data.UsageDayObservation
import com.techdivas.stressanalyzer.data.LocalCheckIn
import com.techdivas.stressanalyzer.data.LocalFocusSession
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val Cream = Color(0xFFF7F5EF)
private val Forest = Color(0xFF203D35)
private val Sage = Color(0xFFA9BCA9)
private val Clay = Color(0xFFD89C7C)
private val Sunlight = Color(0xFFE5C77C)
private val Ink = Color(0xFF27342F)
private val Muted = Color(0xFF758078)
private val Line = Color(0xFFE6E6DE)
private const val LOCAL_DEMO_OWNER_ID = "local-demo"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RhythmTheme { RhythmApp() } }
    }
}

@Composable
private fun RhythmTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Forest, onPrimary = Color.White,
        secondary = Sage, background = Cream, surface = Color.White, onSurface = Ink,
        onBackground = Ink, tertiary = Clay), content = content)
}

private enum class Tab(val label: String, val mark: String) {
    Today("Today", "◉"), Patterns("Patterns", "▤"), Journal("Journal", "♡"), You("You", "○")
}

@Composable
private fun RhythmApp() {
    val rhythmViewModel: RhythmViewModel = viewModel()
    val checkIn = rhythmViewModel.todayCheckIn?.let { CheckIn(it.mood, it.stress, it.energy, it.fatigue, it.tags.toSet(), it.note) }
    var tab by remember { mutableStateOf(Tab.Today) }
    var timerOpen by remember { mutableStateOf(false) }
    var detailOpen by remember { mutableStateOf(false) }
    var checkInOpen by remember { mutableStateOf(false) }
    val usageSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        rhythmViewModel.refreshUsageAccessState()
    }
    LaunchedEffect(Unit) { rhythmViewModel.refreshUsageAccessState() }
    Surface(Modifier.fillMaxSize(), color = Cream) {
        if (timerOpen) TimerScreen(
            rhythmViewModel.account?.uid ?: LOCAL_DEMO_OWNER_ID,
            rhythmViewModel.focusHistory.count { it.sessionType == "focus" && it.status == "completed" && it.localDate == LocalDate.now() },
            onBack = { timerOpen = false }, onSessionSaved = { rhythmViewModel.saveFocusSession(it) }
        )
        else if (detailOpen) InsightScreen(rhythmViewModel.usageDays.firstOrNull { it.localDate == LocalDate.now() }, rhythmViewModel.usageDays, rhythmViewModel.remoteInsights, { id, feedback -> rhythmViewModel.sendInsightFeedback(id, feedback) }, onBack = { detailOpen = false })
        else if (checkInOpen) CheckInScreen(checkIn, onSave = { rhythmViewModel.saveCheckIn(it.mood, it.stress, it.energy, it.fatigue, it.tags.toList(), it.note); checkInOpen = false }, onSkip = { checkInOpen = false })
        else Scaffold(containerColor = Cream, bottomBar = {
            NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                Tab.entries.forEach { item -> NavigationBarItem(selected = tab == item,
                    onClick = { tab = item }, icon = { Text(item.mark, fontSize = 21.sp) },
                    label = { Text(item.label) }, colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Forest, selectedTextColor = Forest,
                        indicatorColor = Sage.copy(alpha = .36f), unselectedIconColor = Muted, unselectedTextColor = Muted)) }
            }
        }) { padding ->
            when (tab) {
                Tab.Today -> TodayScreen(
                    today = rhythmViewModel.usageDays.firstOrNull { it.localDate == LocalDate.now() },
                    usageDays = rhythmViewModel.usageDays,
                    usageAccessGranted = rhythmViewModel.usageAccessGranted,
                    usageLoading = rhythmViewModel.usageLoading,
                    usageMessage = rhythmViewModel.usageMessage,
                    sessions = rhythmViewModel.focusHistory.count { it.sessionType == "focus" && it.status == "completed" && it.localDate == LocalDate.now() },
                    checkedIn = checkIn != null,
                    onEnableUsage = { usageSettings.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                    onRefreshUsage = { rhythmViewModel.refreshUsage() },
                    onStart = { timerOpen = true }, onInsight = { detailOpen = true },
                    onCheckIn = { checkInOpen = true }, modifier = Modifier.padding(padding)
                )
                Tab.Patterns -> PatternsScreen(rhythmViewModel.usageDays, Modifier.padding(padding))
                Tab.Journal -> JournalScreen(rhythmViewModel.checkInHistory, onCheckIn = { checkInOpen = true }, onDelete = { rhythmViewModel.deleteCheckIn(it) }, modifier = Modifier.padding(padding))
                Tab.You -> ProfileScreen(rhythmViewModel, Modifier.padding(padding))
            }
        }
    }
}

@Composable
private fun Page(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 24.dp, bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Column(verticalArrangement = Arrangement.spacedBy(16.dp), content = content) }
    }
}

@Composable
private fun TodayScreen(
    today: UsageDayObservation?,
    usageDays: List<UsageDayObservation>,
    usageAccessGranted: Boolean,
    usageLoading: Boolean,
    usageMessage: String,
    sessions: Int,
    checkedIn: Boolean,
    onEnableUsage: () -> Unit,
    onRefreshUsage: () -> Unit,
    onStart: () -> Unit,
    onInsight: () -> Unit,
    onCheckIn: () -> Unit,
    modifier: Modifier = Modifier
) {
    val comparison = localScreenComparison(today, usageDays)
    Page(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d")).uppercase(), fontSize = 11.sp, color = Muted, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                Text("Find your rhythm.", fontSize = 27.sp, color = Forest, fontWeight = FontWeight.Bold)
            }
            Box(Modifier.size(44.dp).background(Sage.copy(alpha = .4f), CircleShape), contentAlignment = Alignment.Center) {
                Text("R", color = Forest, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        }
        Text("Your phone reports the sum of per-app foreground time. Overlapping app use may be counted more than once; it cannot tell us what you were doing or how you felt.", color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
        if (!usageAccessGranted) {
            CardBox(color = Forest) {
                Text("Usage Access is needed", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("RHYTHM reads app-usage totals only after you enable Usage Access in Android Settings. No message contents or notifications are collected.", color = Color.White.copy(alpha = .84f), fontSize = 13.sp, lineHeight = 19.sp)
                Spacer(Modifier.height(10.dp))
                Button(onClick = onEnableUsage, colors = ButtonDefaults.buttonColors(containerColor = Sunlight, contentColor = Forest)) { Text("Open Usage Access settings") }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric("APP FOREGROUND", today?.let { formatDuration(it.screenTimeMillis) } ?: "—", "sum of app foreground time", Sage, Modifier.weight(1f))
                Metric("FOCUS SESSIONS", sessions.toString(), "completed in this open session", Sunlight, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (usageDays.isEmpty()) "No usage observations saved yet." else "Saved observations: " + usageDays.size + " days", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onRefreshUsage, enabled = !usageLoading) { Text("Refresh", color = Forest) }
            }
            if (usageLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (usageMessage.isNotBlank()) Text(usageMessage, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
            CardBox {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("TODAY'S OBSERVATION", fontSize = 11.sp, color = Forest, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Tag(comparison.category.uppercase(), if (comparison.category == "insufficient_data") Line else Sage.copy(alpha = .45f))
                }
                Spacer(Modifier.height(10.dp))
                Text(comparison.headline, fontSize = 19.sp, color = Forest, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(comparison.explanation, fontSize = 13.sp, color = Muted, lineHeight = 19.sp)
                if (today != null) {
                    Spacer(Modifier.height(10.dp))
                    Text("Observed " + formatDuration(today.screenTimeMillis) + " · " + comparison.baselineText, fontSize = 12.sp, color = Ink)
                    Spacer(Modifier.height(4.dp))
                    Text("Source: Android Usage Stats · " + today.timeZone + " · refreshed " + today.collectedAt.atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")), fontSize = 11.sp, color = Muted)
                }
                Spacer(Modifier.height(10.dp))
                Text("View evidence and limits  →", color = Forest, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = onInsight))
            }
            CardBox {
                Text("APPS WITH RECORDED USAGE", fontSize = 11.sp, color = Forest, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                if (today == null || today.apps.isEmpty()) {
                    Text("No app-usage values were returned for today.", color = Muted, fontSize = 13.sp)
                } else {
                    today.apps.take(8).forEach { app ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(app.appLabel, color = Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(formatDuration(app.foregroundMillis), color = Forest, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
        CardBox {
            Text("FOCUS TIMER", fontSize = 11.sp, color = Forest, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(7.dp))
            Text("Log a focus session when you choose to start one.", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            Button(onClick = onStart, shape = RoundedCornerShape(14.dp)) { Text("Start a focus session") }
        }
        CardBox {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Daily check-in", fontSize = 17.sp, color = Forest, fontWeight = FontWeight.SemiBold)
                    Text(if (checkedIn) "Saved for this open session · tap to edit" else "Optional reflection; never inferred from app use.", fontSize = 13.sp, color = Muted)
                }
                Button(onClick = onCheckIn, shape = RoundedCornerShape(12.dp)) { Text(if (checkedIn) "Edit" else "Check in") }
            }
        }
    }
}@Composable
private fun Metric(label: String, value: String, note: String, tint: Color, modifier: Modifier = Modifier) {
    CardBox(modifier = modifier, padding = 14) {
        Box(Modifier.size(8.dp).background(tint, CircleShape)); Spacer(Modifier.height(10.dp)); Text(label, fontSize = 10.sp, color = Muted, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text(value, fontSize = 22.sp, color = Forest, fontWeight = FontWeight.Bold); Text(note, fontSize = 11.sp, color = Muted)
    }
}

@Composable
private fun CardBox(modifier: Modifier = Modifier, color: Color = Color.White, padding: Int = 18, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier, shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = color), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) { Column(Modifier.fillMaxWidth().padding(padding.dp), content = content) }
}

@Composable
private fun Tag(text: String, color: Color = Sage.copy(alpha = .4f)) { Box(Modifier.background(color, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp)) { Text(text, fontSize = 10.sp, color = Forest, fontWeight = FontWeight.Bold) } }

@Composable
private fun Heading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, fontSize = 25.sp, color = Forest, fontWeight = FontWeight.Bold)
        Text(subtitle, fontSize = 13.sp, color = Muted, lineHeight = 19.sp)
    }
}

private data class LocalScreenComparison(val category: String, val headline: String, val explanation: String, val baselineText: String)

private fun localScreenComparison(today: UsageDayObservation?, records: List<UsageDayObservation>): LocalScreenComparison {
    if (today == null) return LocalScreenComparison(
        "insufficient_data", "No usage observation for today", "Refresh after enabling Usage Access. Missing values are not treated as zero.", "baseline unavailable"
    )
    val history = records.filter { it.localDate.isBefore(today.localDate) }.map { it.screenTimeMillis / 60_000.0 }.sorted()
    if (history.size < 7) return LocalScreenComparison(
        "insufficient_data", "Building your personal baseline", "At least seven valid earlier usage days are needed before comparing today's app foreground usage.", "baseline unavailable · " + history.size + " of 7 historical days"
    )
    val baseline = median(history)
    val mad = median(history.map { kotlin.math.abs(it - baseline) }.sorted())
    val observed = today.screenTimeMillis / 60_000.0
    val difference = observed - baseline
    val threshold = maxOf(3.0 * mad, kotlin.math.abs(baseline) * .2, 1.0)
    val changed = kotlin.math.abs(difference) > threshold
    val differenceText = if (difference >= 0) "higher" else "lower"
    val headline = if (changed) "Screen time is $differenceText than your recent pattern" else "Screen time is within your recent pattern"
    val explanation = if (changed) "This is a single-domain change in Android-reported foreground time. It does not explain why an app was used." else "Today's Android-reported foreground time is near your personal historical median. This is descriptive, not causal."
    return LocalScreenComparison(if (changed) "changed" else "typical", headline, explanation,
        "baseline " + formatDuration((baseline * 60_000).toLong()) + " · " + history.size + " historical days")
}

private fun median(sortedValues: List<Double>): Double {
    val middle = sortedValues.size / 2
    return if (sortedValues.size % 2 == 0) (sortedValues[middle - 1] + sortedValues[middle]) / 2.0 else sortedValues[middle]
}

private fun formatDuration(millis: Long): String {
    val minutes = (millis.coerceAtLeast(0L) / 60_000L)
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return if (hours == 0L) "${remainingMinutes}m" else "${hours}h ${remainingMinutes}m"
}

@Composable
private fun PatternsScreen(usageDays: List<UsageDayObservation>, modifier: Modifier = Modifier) {
    var period by remember { mutableIntStateOf(7) }
    val zone = java.time.ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val firstDate = today.minusDays((period - 1).toLong())
    val byDate = usageDays.associateBy { it.localDate }
    val values = (0 until period).map { offset ->
        val date = firstDate.plusDays(offset.toLong())
        date to byDate[date]?.screenTimeMillis
    }
    Page(modifier) {
        Heading("Your patterns", "Only Android-reported usage observations appear here.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7, 14, 30).forEach { days ->
                FilterChip(selected = period == days, onClick = { period = days }, label = { Text("$days days") })
            }
        }
        UsageTrendCard(values)
        val availableApps = usageDays.filter { !it.localDate.isBefore(firstDate) && !it.localDate.isAfter(today) }.flatMap { it.apps }
        CardBox {
            Text("APP USAGE", fontSize = 11.sp, color = Forest, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (availableApps.isEmpty()) Text("No per-app usage records are available in this range.", color = Muted, fontSize = 13.sp)
            else availableApps.groupBy { it.appLabel }.mapValues { (_, rows) -> rows.sumOf { it.foregroundMillis } }
                .entries.sortedByDescending { it.value }.take(12).forEach { (label, duration) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(label, color = Ink, fontSize = 13.sp)
                            Text("Purpose not confirmed", color = Muted, fontSize = 11.sp)
                        }
                        Text(formatDuration(duration), color = Forest, fontSize = 13.sp)
                    }
                }
        }
        CardBox {
            Text("OTHER PERSONAL SIGNALS", fontSize = 11.sp, color = Forest, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Mood, focus-session history, and logged breaks will appear after their records are stored locally. No values are estimated for missing days.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun UsageTrendCard(values: List<Pair<LocalDate, Long?>>) {
    val present = values.mapNotNull { it.second }
    CardBox {
        Text("APP FOREGROUND USAGE", color = Forest, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text("Sum of Android-reported app foreground time · hours per local day", color = Muted, fontSize = 12.sp)
        if (present.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("No usage history in this period. Enable Usage Access and refresh to collect available records.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
        } else {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                val maxValue = present.maxOrNull()?.coerceAtLeast(1L) ?: 1L
                values.forEach { (_, value) ->
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                        if (value != null) {
                            Box(Modifier.fillMaxWidth().fillMaxHeight((value.toFloat() / maxValue).coerceIn(.02f, 1f)).background(Forest.copy(alpha = .76f), RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)))
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("Bars show reported days; blank columns mean Android returned no observation.", color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(5.dp))
            Text(values.first().first.format(DateTimeFormatter.ofPattern("MMM d")) + " — " + values.last().first.format(DateTimeFormatter.ofPattern("MMM d")), color = Muted, fontSize = 11.sp)
        }
    }
}

private data class CheckIn(val mood: Int, val stress: Int, val energy: Int, val fatigue: Int, val tags: Set<String>, val note: String = "")

@Composable
private fun CheckInScreen(saved: CheckIn?, onSave: (CheckIn) -> Unit, onSkip: () -> Unit) {
    var mood by remember { mutableIntStateOf(saved?.mood ?: 3) }
    var stress by remember { mutableIntStateOf(saved?.stress ?: 3) }
    var energy by remember { mutableIntStateOf(saved?.energy ?: 3) }
    var fatigue by remember { mutableIntStateOf(saved?.fatigue ?: 3) }
    val tags = listOf("Exam", "Deadline", "Travel", "Exercise")
    val selected = remember(saved) { mutableStateListOf<String>().apply { addAll(saved?.tags.orEmpty()) } }
    var note by remember(saved) { mutableStateOf(saved?.note.orEmpty()) }
    Page {
        Heading(if (saved == null) "A moment for you" else "Edit your check-in", "There are no right answers. This reflection is optional and is never inferred from app use.")
        RatingRow("Mood", "1 = low, 5 = high", mood) { mood = it }
        RatingRow("Stress", "1 = low, 5 = high", stress) { stress = it }
        RatingRow("Energy", "1 = low, 5 = high", energy) { energy = it }
        RatingRow("Mental fatigue", "1 = low, 5 = high", fatigue) { fatigue = it }
        CardBox { Text("ANYTHING SHAPING TODAY?", fontSize = 11.sp, color = Forest, fontWeight = FontWeight.Bold); Spacer(Modifier.height(10.dp)); FlowTags(tags, selected) }
        CardBox { Text("A NOTE (OPTIONAL)", fontSize = 11.sp, color = Forest, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp)); OutlinedTextField(note, { note = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Add a thought for your journal") }, minLines = 2) }
        Button(onClick = { onSave(CheckIn(mood, stress, energy, fatigue, selected.toSet(), note)) }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) { Text("Save check-in") }
        TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Skip for now", color = Muted) }
    }
}

@Composable
private fun RatingRow(title: String, question: String, rating: Int, onChange: (Int) -> Unit) {
    CardBox { Text(title, color = Forest, fontWeight = FontWeight.SemiBold, fontSize = 16.sp); Text(question, color = Muted, fontSize = 12.sp); Spacer(Modifier.height(10.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { (1..5).forEach { n -> Box(Modifier.size(44.dp).background(if (n == rating) Forest else Cream, CircleShape).clickable { onChange(n) }, contentAlignment = Alignment.Center) { Text("$n", color = if (n == rating) Color.White else Ink, fontWeight = FontWeight.SemiBold) } } }; Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("A little", fontSize = 10.sp, color = Muted); Text("A lot", fontSize = 10.sp, color = Muted) } }
}

@Composable
private fun FlowTags(options: List<String>, selected: MutableList<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { options.chunked(2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { tag -> FilterChip(selected = tag in selected, onClick = { if (tag in selected) selected.remove(tag) else selected.add(tag) }, label = { Text(tag) }) } } } }
}

@Composable
private fun JournalScreen(checkIns: List<LocalCheckIn>, onCheckIn: () -> Unit, onDelete: (String) -> Unit, modifier: Modifier = Modifier) {
    Page(modifier) {
        Heading("Your journal", "Only check-ins you chose to save appear here.")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("CHECK-IN HISTORY", fontSize = 11.sp, color = Muted, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(onClick = onCheckIn, shape = RoundedCornerShape(12.dp)) { Text("Check in") }
        }
        if (checkIns.isEmpty()) {
            CardBox {
                Text("No saved check-ins", color = Forest, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("Mood and context are never inferred from app usage. Your first saved reflection will appear here.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
            }
        } else {
            checkIns.forEach { item ->
                CardBox {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.localDate.format(DateTimeFormatter.ofPattern("EEEE, MMM d")), color = Forest, fontWeight = FontWeight.SemiBold)
                            Text("Saved " + item.recordedAt.atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a")), color = Muted, fontSize = 11.sp)
                        }
                        TextButton(onClick = { onDelete(item.id) }) { Text("Delete", color = Clay) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Mood " + item.mood + "/5 · Stress " + item.stress + "/5 · Energy " + item.energy + "/5 · Fatigue " + item.fatigue + "/5", color = Ink, fontSize = 13.sp)
                    if (item.tags.isNotEmpty()) Text(item.tags.joinToString(" · "), color = Forest, fontSize = 12.sp)
                    if (item.note.isNotBlank()) Text(item.note, color = Muted, fontSize = 13.sp)
                }
            }
        }
    }
}@Composable
private fun AccountSyncPanel(viewModel: RhythmViewModel) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showDelete by remember { mutableStateOf(false) }

    if (showDelete) AlertDialog(
        onDismissRequest = { showDelete = false },
        title = { Text("Delete Firebase account?") },
        text = { Text("This deletes your Firebase sign-in account and its acknowledged backend records. Locally stored device usage and records can be removed separately below.") },
        confirmButton = { TextButton(onClick = { viewModel.deleteAccount(); showDelete = false }) { Text("Delete account") } },
        dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } }
    )

    CardBox {
        Text("ACCOUNT & SIGN-IN", fontSize = 11.sp, color = Forest, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text("Explore Demo always works without registering.", color = Muted, fontSize = 12.sp)
        if (viewModel.account == null) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = email, onValueChange = { email = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true, enabled = !viewModel.busy)
            OutlinedTextField(value = password, onValueChange = { password = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Password Â· at least 6 characters") }, singleLine = true, enabled = !viewModel.busy, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !viewModel.busy && email.isNotBlank() && password.length >= 6, onClick = { viewModel.signIn(email, password, false) }) { Text("Sign in") }
                OutlinedButton(enabled = !viewModel.busy && email.isNotBlank() && password.length >= 6, onClick = { viewModel.signIn(email, password, true) }) { Text("Create account") }
            }
        } else {
            Text("Signed in as ${viewModel.account?.email}", color = Forest, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("Authenticated securely with Firebase Email/Password.", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Text("Cloud records require your choice. Groq receives minimized, aggregated evidence only after separate AI consent.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
            Text("Sync includes selected-device app usage, check-ins and focus-session records. Notes go to the RHYTHM backend only when you choose Sync now.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Allow backend sync", modifier = Modifier.weight(1f), color = Ink, fontSize = 13.sp)
                Switch(checked = viewModel.cloudSyncEnabled, enabled = !viewModel.busy, onCheckedChange = { viewModel.updateProcessingConsent(it, if (it) viewModel.aiConsent else false) })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Allow AI explanations", color = Ink, fontSize = 13.sp)
                    Text("Groq is a third-party provider; it does not receive notes, email, app names or Firebase ID.", color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
                }
                Switch(checked = viewModel.aiConsent, enabled = !viewModel.busy && viewModel.cloudSyncEnabled, onCheckedChange = { viewModel.updateProcessingConsent(true, it) })
            }
            if (!viewModel.cloudSyncEnabled) Text("Consent controls are off by default. Local records are not uploaded.", color = Muted, fontSize = 11.sp)
            if (viewModel.cloudSyncEnabled) {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { viewModel.syncLocalRecords() }, enabled = !viewModel.syncBusy, modifier = Modifier.fillMaxWidth()) {
                    if (viewModel.syncBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp)); Text("Sync now")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                TextButton(onClick = { viewModel.signOut() }, enabled = !viewModel.busy) { Text("Sign out", color = Forest) }
                TextButton(onClick = { showDelete = true }, enabled = !viewModel.busy) { Text("Delete account", color = Clay) }
            }
        }
        if (viewModel.busy) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Text(viewModel.message, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun ProfileScreen(viewModel: RhythmViewModel, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var confirmLocalDelete by remember { mutableStateOf(false) }
    if (confirmLocalDelete) AlertDialog(
        onDismissRequest = { confirmLocalDelete = false },
        title = { Text("Delete local RHYTHM data?") },
        text = { Text("This removes check-ins, focus sessions and stored Usage Stats observations from this device. It does not change your backend account data.") },
        confirmButton = { TextButton(onClick = { viewModel.deleteLocalData(); confirmLocalDelete = false }) { Text("Delete local data") } },
        dismissButton = { TextButton(onClick = { confirmLocalDelete = false }) { Text("Cancel") } }
    )
    fun shareJson(json: String, title: String) {
        val intent = Intent(Intent.ACTION_SEND).apply { type = "application/json"; putExtra(Intent.EXTRA_TEXT, json) }
        context.startActivity(Intent.createChooser(intent, title))
    }
    Page(modifier) {
        Heading("You", "Your account, preferences and privacy controls.")
        AccountSyncPanel(viewModel)
        CardBox {
            Text("USAGE ACCESS", color = Forest, fontWeight = FontWeight.Bold)
            Text("Android provides app foreground totals after you enable Usage Access. RHYTHM does not collect message contents, notifications, location, microphone or camera data.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
        }
        CardBox {
            Text("LOCAL DATA", color = Forest, fontWeight = FontWeight.Bold)
            Text("Check-ins and focus sessions are stored on this device. App-usage observations describe this device and are stored locally. You can export or delete these local records below.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { shareJson(viewModel.exportLocalData(), "Export RHYTHM local data") }, modifier = Modifier.fillMaxWidth()) { Text("Export local data") }
            TextButton(onClick = { confirmLocalDelete = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Delete local data", color = Clay) }
            if (viewModel.account != null) {
                OutlinedButton(onClick = { viewModel.exportCloudData { json -> if (json != null) shareJson(json, "Export RHYTHM backend data") } }, enabled = !viewModel.busy, modifier = Modifier.fillMaxWidth()) { Text("Export backend data") }
            }
        }
    }
}

@Composable
private fun InsightScreen(today: UsageDayObservation?, records: List<UsageDayObservation>, remoteInsights: List<Map<String, Any?>>, onFeedback: (String, String) -> Unit, onBack: () -> Unit) {
    val comparison = localScreenComparison(today, records)
    Page {
        Text("Back to today", color = Forest, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onBack))
        Heading("Usage observation", "A deterministic comparison using Android-reported foreground time.")
        CardBox {
            Tag(comparison.category.uppercase(), if (comparison.category == "insufficient_data") Line else Sage.copy(alpha = .45f))
            Spacer(Modifier.height(10.dp))
            Text(comparison.headline, color = Forest, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(7.dp))
            Text(comparison.explanation, color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
        }
        CardBox {
            Text("OBSERVED VALUE AND BASELINE", color = Forest, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            Text("Observed: " + (today?.let { formatDuration(it.screenTimeMillis) } ?: "unavailable"), color = Ink, fontSize = 14.sp)
            Text("Baseline: " + comparison.baselineText, color = Ink, fontSize = 14.sp)
            Text("Valid historical days required: 7; current history: " + records.count { today == null || it.localDate.isBefore(today.localDate) }, color = Muted, fontSize = 12.sp)
        }
        CardBox {
            Text("SUPPORTING SIGNALS", color = Forest, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            if (today == null) Text("No current-day observation is available. Enable Usage Access and refresh.", color = Muted, fontSize = 13.sp)
            else {
                Text("Source: Android UsageStatsManager", color = Muted, fontSize = 13.sp)
                Text("Local date: " + today.localDate + " · time zone: " + today.timeZone, color = Muted, fontSize = 13.sp)
                Text("Android-reported foreground duration: " + formatDuration(today.screenTimeMillis), color = Muted, fontSize = 13.sp)
                today.apps.take(8).forEach { app -> Text(app.appLabel + " · " + formatDuration(app.foregroundMillis), color = Muted, fontSize = 12.sp) }
            }
        }
        CardBox {
            Text("LIMITS AND UNCERTAINTY", color = Forest, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            Text("Usage statistics cannot identify purpose, productivity, mood, or why an app was opened. This is a single-domain observation. Missing dates are not filled with zero, and no health conclusion is made.", color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
        }
        Heading("Synced findings", "Backend analysis is available only for records you explicitly synced.")
        if (remoteInsights.isEmpty()) {
            CardBox { Text("No backend findings", color = Forest, fontWeight = FontWeight.SemiBold); Text("Choose Sync now in You after enabling cloud consent.", color = Muted, fontSize = 13.sp) }
        } else remoteInsights.take(8).forEach { finding ->
            CardBox {
                val category = finding["category"]?.toString()?.replace('_', ' ') ?: "insufficient data"
                Tag(category.uppercase())
                Spacer(Modifier.height(7.dp))
                finding["headline"]?.toString()?.takeIf { it.isNotBlank() }?.let { Text(it, color = Forest, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
                Text(finding["explanation"]?.toString() ?: "No explanation available.", color = Ink, fontSize = 13.sp, lineHeight = 19.sp)
                Text("Model status: " + (finding["generation_status"]?.toString() ?: "deterministic_only"), color = Muted, fontSize = 11.sp)
                val action = finding["recommendation"]?.toString()
                if (!action.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp)); Text(action, color = Forest, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    val id = finding["id"]?.toString()
                    if (!id.isNullOrBlank()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { onFeedback(id, "helpful") }) { Text("Helpful") }
                        TextButton(onClick = { onFeedback(id, "not_helpful") }) { Text("Not useful") }
                    }
                }
            }
        }
    }
}@Composable
private fun TimerScreen(ownerId: String, completedToday: Int, onBack: () -> Unit, onSessionSaved: (LocalFocusSession) -> Unit) {
    var focusMinutes by remember { mutableIntStateOf(45) }
    var breakMinutes by remember { mutableIntStateOf(15) }
    var isFocus by remember { mutableStateOf(true) }
    var remaining by remember { mutableIntStateOf(focusMinutes * 60) }
    var running by remember { mutableStateOf(false) }
    var cycle by remember { mutableIntStateOf(1) }
    var settings by remember { mutableStateOf(false) }
    var activeId by remember { mutableStateOf<String?>(null) }
    var activeType by remember { mutableStateOf<String?>(null) }
    var activeStartedAt by remember { mutableStateOf<java.time.Instant?>(null) }
    var activePlannedSeconds by remember { mutableIntStateOf(0) }
    var activeElapsedSeconds by remember { mutableIntStateOf(0) }
    val phaseDuration = (if (isFocus) focusMinutes else breakMinutes) * 60
    val zone = java.time.ZoneId.systemDefault()

    fun clearActiveSession() {
        activeId = null
        activeType = null
        activeStartedAt = null
        activePlannedSeconds = 0
        activeElapsedSeconds = 0
    }

    fun persistActive(status: String, endedAt: java.time.Instant? = null, actualOverride: Int? = null, keepActive: Boolean = false) {
        val id = activeId ?: return
        val start = activeStartedAt ?: return
        onSessionSaved(LocalFocusSession(
            id = id,
            ownerId = ownerId,
            sessionType = activeType ?: if (isFocus) "focus" else "break",
            plannedSeconds = activePlannedSeconds,
            actualSeconds = (actualOverride ?: activeElapsedSeconds).coerceIn(0, activePlannedSeconds),
            startedAt = start,
            endedAt = endedAt,
            status = status,
            cycle = cycle,
            localDate = start.atZone(zone).toLocalDate(),
            timeZone = zone.id
        ))
        if (!keepActive) clearActiveSession()
    }

    fun beginPhaseSession() {
        if (activeId != null) return
        val now = java.time.Instant.now()
        activeId = java.util.UUID.randomUUID().toString()
        activeType = if (isFocus) "focus" else "break"
        activeStartedAt = now
        activePlannedSeconds = phaseDuration
        activeElapsedSeconds = 0
        onSessionSaved(LocalFocusSession(
            activeId!!, ownerId, activeType!!, activePlannedSeconds, 0, now, null,
            "running", cycle, now.atZone(zone).toLocalDate(), zone.id
        ))
    }

    LaunchedEffect(running) {
        while (running) {
            delay(1000)
            if (!running) break
            activeElapsedSeconds = (activeElapsedSeconds + 1).coerceAtMost(activePlannedSeconds)
            if (remaining <= 1) {
                persistActive("completed", java.time.Instant.now(), activePlannedSeconds)
                if (isFocus) {
                    isFocus = false
                    remaining = breakMinutes * 60
                } else {
                    isFocus = true
                    cycle++
                    remaining = focusMinutes * 60
                }
                if (running) beginPhaseSession()
            } else {
                remaining--
            }
        }
    }

    if (settings) AlertDialog(
        onDismissRequest = { settings = false },
        title = { Text("Timer lengths") },
        text = {
            Column {
                Text("Focus · " + focusMinutes + " min")
                Slider(focusMinutes.toFloat(), { focusMinutes = it.toInt().coerceIn(5, 90); if (isFocus && !running && activeId == null) remaining = focusMinutes * 60 }, valueRange = 5f..90f, steps = 16)
                Text("Break · " + breakMinutes + " min")
                Slider(breakMinutes.toFloat(), { breakMinutes = it.toInt().coerceIn(5, 30); if (!isFocus && !running && activeId == null) remaining = breakMinutes * 60 }, valueRange = 5f..30f, steps = 4)
            }
        },
        confirmButton = { TextButton(onClick = { settings = false }) { Text("Done") } }
    )

    Column(Modifier.fillMaxSize().background(Cream).padding(horizontal = 24.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("←  Today", color = Forest, modifier = Modifier.clickable {
                running = false
                persistActive("interrupted", java.time.Instant.now())
                onBack()
            })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { settings = true }) { Text("Settings", color = Forest) }
        }
        Spacer(Modifier.height(22.dp))
        Text("A LITTLE SPACE TO FOCUS", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = isFocus, onClick = {
                running = false
                persistActive("interrupted", java.time.Instant.now())
                isFocus = true
                remaining = focusMinutes * 60
            }, label = { Text("Focus") })
            FilterChip(selected = !isFocus, onClick = {
                running = false
                persistActive("interrupted", java.time.Instant.now())
                isFocus = false
                remaining = breakMinutes * 60
            }, label = { Text("Break") })
        }
        Spacer(Modifier.height(33.dp))
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(260.dp)) {
                val stroke = 12.dp.toPx()
                drawArc(Sage.copy(alpha = .35f), -90f, 360f, false, style = Stroke(stroke, cap = StrokeCap.Round))
                drawArc(Forest, -90f, 360f * (remaining.toFloat() / phaseDuration).coerceIn(0f, 1f), false, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("%02d:%02d".format(remaining / 60, remaining % 60), fontSize = 52.sp, color = Forest, fontWeight = FontWeight.Light)
                Text(if (isFocus) "FOCUS TIME" else "BREAK TIME", fontSize = 11.sp, color = Muted, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            }
        }
        Spacer(Modifier.height(22.dp))
        Text("Cycle " + cycle + " · " + completedToday + " focus sessions completed today", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                running = false
                persistActive("cancelled", java.time.Instant.now())
                isFocus = true
                remaining = focusMinutes * 60
                cycle = 1
            }, shape = RoundedCornerShape(14.dp)) { Text("Reset", color = Forest) }
            Button(onClick = {
                if (running) {
                    running = false
                    persistActive("running", keepActive = true)
                } else {
                    beginPhaseSession()
                    running = true
                }
            }, modifier = Modifier.height(54.dp).width(150.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Forest)) {
                Text(if (running) "Pause" else if (remaining < phaseDuration) "Resume" else "Start", fontSize = 16.sp)
            }
            OutlinedButton(onClick = {
                running = false
                persistActive("skipped", java.time.Instant.now())
                if (isFocus) {
                    isFocus = false
                    remaining = breakMinutes * 60
                } else {
                    isFocus = true
                    cycle++
                    remaining = focusMinutes * 60
                }
            }, shape = RoundedCornerShape(14.dp)) { Text("Skip", color = Forest) }
        }
        Spacer(Modifier.height(20.dp))
        CardBox {
            Text("Session recording", color = Forest, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("Start, pause, resume, complete, skip, and reset are stored locally with their actual elapsed time.", color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
        }
        Spacer(Modifier.weight(1f))
        Text("Focus " + focusMinutes + " min · Break " + breakMinutes + " min", color = Muted, fontSize = 12.sp)
    }
}
