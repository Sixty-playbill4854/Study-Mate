package com.mk.studymate

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import androidx.compose.runtime.saveable.rememberSaveable

private val NavyBg = Color(0xFF0A1128)
private val CardBg = Color(0xFF131E3F)
private val Blue = Color(0xFF3D8BFF)
private val Muted = Color(0xFF8FA0C8)
private val Ink = Color(0xFFE8EEFF)
private val Good = Color(0xFF4CD6A0)
private val Bad = Color(0xFFFF6B6B)

class MainActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Reminders.ensureChannel(this)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Blue, onPrimary = Color.White, secondary = Blue,
                    background = NavyBg, onBackground = Ink,
                    surface = CardBg, onSurface = Ink,
                    surfaceVariant = CardBg, onSurfaceVariant = Muted,
                    outline = Muted, error = Bad
                )
            ) { App(resumeTick.intValue) }
        }
    }
}

private fun fmtDay(d: String) = LocalDate.parse(d).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
private fun fmtTime(t: String) = LocalTime.parse(t).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

private fun openNotifSettings(c: Context) {
    try {
        c.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)
        )
    } catch (_: ActivityNotFoundException) { }
}

private fun openExactSettings(c: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    try {
        c.startActivity(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${c.packageName}"))
        )
    } catch (_: ActivityNotFoundException) { }
}

@Composable
fun App(resume: Int) {
    val ctx = LocalContext.current
    val dao = remember { Db.get(ctx).dao() }
    val flow = remember { dao.observe() }
    val plans by flow.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var formId by rememberSaveable { mutableStateOf(-1L) } // -1 = list, 0 = new, >0 = edit
    var toDelete by remember { mutableStateOf<Plan?>(null) }
    var permTick by remember { mutableIntStateOf(0) }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permTick++ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    // On every resume: refresh clock and re-register alarms (covers exact-alarm permission changes).
    LaunchedEffect(resume) {
        now = System.currentTimeMillis()
        Reminders.rescheduleAll(ctx)
    }
    LaunchedEffect(Unit) {
        while (true) { delay(30_000); now = System.currentTimeMillis() }
    }

    val notifOk = remember(resume, permTick) { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }
    val exactOk = remember(resume) { Reminders.canExact(ctx) }

    val formPlan = plans.firstOrNull { it.id == formId }

    if (formId == 0L || formPlan != null) {
        BackHandler { formId = -1L }
        PlanForm(
            initial = formPlan,
            onCancel = { formId = -1L },
            onSave = { p ->
                scope.launch {
                    val id = if (p.id == 0L) dao.insert(p) else { dao.update(p); p.id }
                    Reminders.schedule(ctx, p.copy(id = id))
                    formId = -1L
                }
            }
        )
    } else {
        val upcoming = plans.filter { !it.completed && it.startMillis() > now }
        val past = plans.filter { it.completed || it.startMillis() <= now }.reversed()

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { formId = 0L },
                    containerColor = Blue, contentColor = Color.White
                ) { Text("+  New plan", fontWeight = FontWeight.Bold) }
            }
        ) { pad ->
            Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
                Text("MK StudyMate", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink,
                    modifier = Modifier.padding(top = 12.dp))
                Text("Plan it. Study it. Done.", color = Muted)
                Spacer(Modifier.height(12.dp))

                if (!notifOk) Banner(
                    "Notifications are blocked, so reminders cannot be shown. Enable them in settings.",
                    "Open settings"
                ) { openNotifSettings(ctx) }
                if (!exactOk) Banner(
                    "Exact alarms are not allowed. Reminders will still fire, but may arrive a few minutes late.",
                    "Allow exact"
                ) { openExactSettings(ctx) }

                TabRow(selectedTabIndex = tab, containerColor = Color.Transparent, contentColor = Blue) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Upcoming (${upcoming.size})") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Past (${past.size})") })
                }

                val list = if (tab == 0) upcoming else past
                if (list.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (tab == 0) "No upcoming plans.\nTap “+ New plan” to add one." else "Nothing here yet.",
                            color = Muted, textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(top = 12.dp, bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(list, key = { it.id }) { p ->
                            PlanCard(
                                p,
                                onToggle = {
                                    scope.launch {
                                        val u = p.copy(completed = !p.completed)
                                        dao.update(u)
                                        if (u.completed) Reminders.cancel(ctx, u.id) else Reminders.schedule(ctx, u)
                                    }
                                },
                                onEdit = { formId = p.id },
                                onDelete = { toDelete = p }
                            )
                        }
                    }
                }
            }
        }

        toDelete?.let { p ->
            AlertDialog(
                onDismissRequest = { toDelete = null },
                title = { Text("Delete plan?") },
                text = { Text("“${p.subject}: ${p.task}” and its reminder will be removed.") },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch { dao.delete(p); Reminders.cancel(ctx, p.id) }
                        toDelete = null
                    }) { Text("Delete", color = Bad) }
                },
                dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } }
            )
        }
    }
}

@Composable
fun Banner(text: String, action: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2A57)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
    ) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onClick) { Text(action, color = Blue, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun PlanCard(p: Plan, onToggle: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.subject.uppercase(), color = Blue, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                if (p.completed) Text("✓ Completed", color = Good, fontSize = 12.sp)
                else if (p.startMillis() <= System.currentTimeMillis()) Text("Missed", color = Bad, fontSize = 12.sp)
            }
            Text(
                p.task, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                textDecoration = if (p.completed) TextDecoration.LineThrough else null,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text("${fmtDay(p.day)} · ${fmtTime(p.clock)}", color = Muted, fontSize = 13.sp,
                modifier = Modifier.padding(top = 2.dp))
            if (p.notes.isNotBlank()) {
                Text(p.notes, color = Ink.copy(alpha = 0.8f), fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onToggle) {
                    Text(if (p.completed) "Undo" else "Mark done", color = if (p.completed) Muted else Good)
                }
                TextButton(onClick = onEdit) { Text("Edit", color = Blue) }
                TextButton(onClick = onDelete) { Text("Delete", color = Bad) }
            }
        }
    }
}

@Composable
fun PlanForm(initial: Plan?, onCancel: () -> Unit, onSave: (Plan) -> Unit) {
    val ctx = LocalContext.current
    val dflt = remember { LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0) }
    var subject by rememberSaveable { mutableStateOf(initial?.subject ?: "") }
    var task by rememberSaveable { mutableStateOf(initial?.task ?: "") }
    var notes by rememberSaveable { mutableStateOf(initial?.notes ?: "") }
    var day by rememberSaveable { mutableStateOf(initial?.day ?: dflt.toLocalDate().toString()) }
    var clock by rememberSaveable { mutableStateOf(initial?.clock ?: dflt.toLocalTime().toString()) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().background(NavyBg).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(if (initial == null) "New study plan" else "Edit plan", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)

        OutlinedTextField(subject, { subject = it }, label = { Text("Subject") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(task, { task = it }, label = { Text("Chapter / task") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = {
                    val d = LocalDate.parse(day)
                    DatePickerDialog(ctx, { _, y, m, dd -> day = LocalDate.of(y, m + 1, dd).toString() },
                        d.year, d.monthValue - 1, d.dayOfMonth).apply {
                        if (initial == null) datePicker.minDate =
                            LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    }.show()
                },
                modifier = Modifier.weight(1f)
            ) { Text(fmtDay(day)) }
            OutlinedButton(
                onClick = {
                    val t = LocalTime.parse(clock)
                    TimePickerDialog(ctx, { _, h, m -> clock = LocalTime.of(h, m).toString() },
                        t.hour, t.minute, DateFormat.is24HourFormat(ctx)).show()
                },
                modifier = Modifier.weight(1f)
            ) { Text(fmtTime(clock)) }
        }

        OutlinedTextField(notes, { notes = it }, label = { Text("Notes (optional)") }, minLines = 3, modifier = Modifier.fillMaxWidth())

        error?.let { Text(it, color = Bad, fontSize = 14.sp) }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(
                onClick = {
                    val s = subject.trim()
                    val tk = task.trim()
                    val completed = initial?.completed ?: false
                    val timeChanged = initial == null || initial.day != day || initial.clock != clock
                    val start = Plan(0, s, tk, day, clock).startMillis()
                    error = when {
                        s.isEmpty() -> "Enter a subject."
                        tk.isEmpty() -> "Enter a chapter or task."
                        !completed && timeChanged && start <= System.currentTimeMillis() ->
                            "Pick a start time in the future so the reminder can fire."
                        else -> null
                    }
                    if (error == null) onSave(Plan(initial?.id ?: 0, s, tk, day, clock, notes.trim(), completed))
                },
                modifier = Modifier.weight(1f)
            ) { Text("Save", fontWeight = FontWeight.Bold) }
        }
    }
}
