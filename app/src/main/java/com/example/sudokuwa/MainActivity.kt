package com.example.sudokuwa

import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

// ---------- Палитра ----------
private val Washi      = Color(0xFFF5EFE6)
private val Sumi       = Color(0xFF2B2B2B)
private val Sakura     = Color(0xFFF4B6C2)
private val SakuraDeep = Color(0xFFE38AA3)
private val Ai         = Color(0xFF3B5B7A)
private val ErrorRed   = Color(0xFFB94A48)

// ---------- Модель ----------
data class Cell(
    val value: Int = 0,
    val isFixed: Boolean = false,
    val notes: Set<Int> = emptySet(),
    val isError: Boolean = false
)

enum class Difficulty { EASY, MEDIUM, HARD }

data class GameState(
    val cells: List<Cell> = List(81) { Cell() },
    val selected: Int? = null,
    val notesMode: Boolean = false,
    val mistakes: Int = 0,
    val hintsLeft: Int = 3,
    val difficulty: Difficulty = Difficulty.EASY,
    val isWon: Boolean = false,
    val dialogue: String = ""
)

private enum class Screen { MENU, GAME }

// ---------- Движок судоку ----------
object Sudoku {
    fun generateSolved(): Array<IntArray> {
        val b = Array(9) { IntArray(9) }
        fill(b)
        return b
    }

    private fun fill(b: Array<IntArray>): Boolean {
        for (r in 0..8) for (c in 0..8) if (b[r][c] == 0) {
            for (n in (1..9).shuffled()) {
                if (isValid(b, r, c, n)) {
                    b[r][c] = n
                    if (fill(b)) return true
                    b[r][c] = 0
                }
            }
            return false
        }
        return true
    }

    fun isValid(b: Array<IntArray>, row: Int, col: Int, n: Int): Boolean {
        for (i in 0..8) {
            if (b[row][i] == n && i != col) return false
            if (b[i][col] == n && i != row) return false
        }
        val br = (row / 3) * 3
        val bc = (col / 3) * 3
        for (r in br until br + 3) for (c in bc until bc + 3)
            if (b[r][c] == n && (r != row || c != col)) return false
        return true
    }

    fun makePuzzle(solved: Array<IntArray>, d: Difficulty): Array<IntArray> {
        val p = Array(9) { solved[it].clone() }
        val remove = when (d) {
            Difficulty.EASY -> 35
            Difficulty.MEDIUM -> 45
            Difficulty.HARD -> 52
        }
        var removed = 0
        for (idx in (0 until 81).shuffled()) {
            if (removed >= remove) break
            val r = idx / 9
            val c = idx % 9
            if (p[r][c] == 0) continue
            p[r][c] = 0
            removed++
        }
        return p
    }

    fun isSolved(b: Array<IntArray>): Boolean {
        for (r in 0..8) for (c in 0..8) {
            val v = b[r][c]
            if (v == 0) return false
            b[r][c] = 0
            val ok = isValid(b, r, c, v)
            b[r][c] = v
            if (!ok) return false
        }
        return true
    }
}

// ---------- ViewModel ----------
class GameViewModel : ViewModel() {
    private val _state = MutableStateFlow(GameState())
    val state: StateFlow<GameState> = _state
    private var solution = Array(9) { IntArray(9) }

    init { newGame(Difficulty.EASY) }

    fun newGame(d: Difficulty) {
        val solved = Sudoku.generateSolved()
        val puzzle = Sudoku.makePuzzle(solved, d)
        solution = solved
        val cells = puzzle.flatMap { row -> row.map { v -> Cell(value = v, isFixed = v != 0) } }
        _state.value = GameState(
            cells = cells,
            difficulty = d,
            dialogue = when (d) {
                Difficulty.EASY   -> "Не спеши."
                Difficulty.MEDIUM -> "Покажи, на что ты способен."
                Difficulty.HARD   -> "Тишина. Только числа."
            }
        )
    }

    fun select(i: Int) = _state.update { it.copy(selected = i) }
    fun toggleNotes() = _state.update { it.copy(notesMode = !it.notesMode) }

    fun erase() {
        val s = _state.value
        val idx = s.selected ?: return
        val cell = s.cells[idx]
        if (cell.isFixed) return
        updateCell(idx, cell.copy(value = 0, isError = false, notes = emptySet()))
    }

    fun input(n: Int) {
        val s = _state.value
        val idx = s.selected ?: return
        val cell = s.cells[idx]
        if (cell.isFixed) return
        if (s.notesMode) {
            val notes = if (n in cell.notes) cell.notes - n else cell.notes + n
            updateCell(idx, cell.copy(notes = notes))
        } else {
            val correct = solution[idx / 9][idx % 9]
            val err = n != correct
            updateCell(idx, cell.copy(value = n, isError = err, notes = emptySet()))
            if (err) _state.update {
                it.copy(mistakes = it.mistakes + 1, dialogue = "Не спеши.")
            } else checkWin()
        }
    }

    fun hint() {
        val s = _state.value
        if (s.hintsLeft <= 0) return
        val idx = s.selected?.takeIf { s.cells[it].value == 0 }
            ?: s.cells.indexOfFirst { it.value == 0 }
        if (idx < 0) return
        val correct = solution[idx / 9][idx % 9]
        updateCell(idx, s.cells[idx].copy(value = correct, isError = false, notes = emptySet()))
        _state.update { it.copy(hintsLeft = it.hintsLeft - 1, dialogue = "Здесь — $correct.") }
        checkWin()
    }

    private fun updateCell(i: Int, c: Cell) = _state.update { s ->
        val list = s.cells.toMutableList()
        list[i] = c
        s.copy(cells = list)
    }

    private fun checkWin() {
        val flat = _state.value.cells.map { it.value }.toIntArray()
        val b = Array(9) { r -> IntArray(9) { c -> flat[r * 9 + c] } }
        if (Sudoku.isSolved(b)) _state.update {
            it.copy(isWon = true, dialogue = "Ты прошёл путь.")
        }
    }
}

// ---------- Activity ----------
class MainActivity : ComponentActivity() {
    private val vm: GameViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = SakuraDeep,
                    background = Washi,
                    surface = Washi,
                    onBackground = Sumi,
                    onSurface = Sumi
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = Washi) {
                    AppRoot(vm)
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.statusBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }
}

@Composable
private fun AppRoot(vm: GameViewModel) {
    var screen by remember { mutableStateOf(Screen.MENU) }

    when (screen) {
        Screen.MENU -> MainMenuScreen(
            onDifficultySelected = { diff ->
                vm.newGame(diff)
                screen = Screen.GAME
            }
        )
        Screen.GAME -> GameScreen(
            vm = vm,
            onBackToMenu = { screen = Screen.MENU }
        )
    }
}

// ---------- Видео-фон ----------
@Composable
private fun VideoBackground(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val uriString = "android.resource://${context.packageName}/${R.raw.loop}"
            setMediaItem(MediaItem.fromUri(uriString))
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f
            playWhenReady = true
            prepare()
        }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        modifier = modifier
    )
}

// ---------- Виньетка ----------
@Composable
private fun Vignette(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRect(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to Color.Transparent,
                    0.55f to Color.Transparent,
                    1.0f to Color.Black.copy(alpha = 0.75f)
                ),
                center = Offset(size.width / 2f, size.height / 2f),
                radius = maxOf(size.width, size.height) * 0.75f
            )
        )
    }
}

// ---------- Главное меню ----------
@Composable
private fun MainMenuScreen(onDifficultySelected: (Difficulty) -> Unit) {
    val context = LocalContext.current
    val hasVideo = remember {
        context.resources.getIdentifier("loop", "raw", context.packageName) != 0
    }
    val fallbackRes = remember {
        context.resources.getIdentifier("garden_menu", "drawable", context.packageName)
    }

    Box(Modifier.fillMaxSize().background(Washi)) {
        if (hasVideo) {
            VideoBackground(Modifier.fillMaxSize())
        } else if (fallbackRes != 0) {
            Image(
                painter = painterResource(fallbackRes),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        Vignette(Modifier.fillMaxSize())

        // Градиенты для читаемости: сверху мягко, снизу сильнее
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.40f),
                        0.20f to Color.Transparent,
                        0.55f to Color.Transparent,
                        1.0f to Color.Black.copy(alpha = 0.65f)
                    )
                )
        )

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(70.dp))

            Text(
                "Судоку",
                fontSize = 52.sp,
                fontWeight = FontWeight.Light,
                color = Washi,
                letterSpacing = 6.sp,
                style = TextStyle(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.7f),
                        offset = Offset(0f, 3f),
                        blurRadius = 12f
                    )
                )
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "г а р м о н и я   ч и с е л",
                fontSize = 12.sp,
                color = Washi.copy(alpha = 0.75f),
                letterSpacing = 4.sp,
                style = TextStyle(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.5f),
                        offset = Offset(0f, 2f),
                        blurRadius = 8f
                    )
                )
            )

            Spacer(Modifier.weight(1f))

            MenuTextButton(
                title = "Лёгкий",
                subtitle = "для спокойного вечера",
                onClick = { onDifficultySelected(Difficulty.EASY) }
            )
            Spacer(Modifier.height(26.dp))
            MenuTextButton(
                title = "Средний",
                subtitle = "требует сосредоточенности",
                onClick = { onDifficultySelected(Difficulty.MEDIUM) }
            )
            Spacer(Modifier.height(26.dp))
            MenuTextButton(
                title = "Сложный",
                subtitle = "для мастеров",
                onClick = { onDifficultySelected(Difficulty.HARD) }
            )

            Spacer(Modifier.height(40.dp))

            MenuTextButton(
                title = "О игре",
                subtitle = null,
                small = true,
                onClick = { /* later */ }
            )

            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun MenuTextButton(
    title: String,
    subtitle: String?,
    small: Boolean = false,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val alpha by animateFloatAsState(if (pressed) 0.55f else 1f, label = "alpha")
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, label = "scale")

    Row(
        modifier = Modifier
            .scale(scale)
            .alpha(alpha)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Тонкая вертикальная черта-«мазок» слева
        Box(
            Modifier
                .width(2.dp)
                .height(if (small) 18.dp else 40.dp)
                .background(Washi.copy(alpha = 0.65f))
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                text = title,
                fontSize = if (small) 18.sp else 30.sp,
                fontWeight = FontWeight.Light,
                color = Washi,
                letterSpacing = if (small) 4.sp else 2.sp,
                style = TextStyle(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.7f),
                        offset = Offset(0f, 2f),
                        blurRadius = 10f
                    )
                )
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = Washi.copy(alpha = 0.65f),
                    letterSpacing = 1.sp,
                    style = TextStyle(
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.5f),
                            offset = Offset(0f, 1f),
                            blurRadius = 6f
                        )
                    )
                )
            }
        }
    }
}

// ---------- Экран игры ----------
@Composable
fun GameScreen(vm: GameViewModel, onBackToMenu: () -> Unit) {
    val s by vm.state.collectAsState()
    val context = LocalContext.current

    val himikoRes = remember {
        context.resources.getIdentifier("himiko", "drawable", context.packageName)
    }

    Box(Modifier.fillMaxSize().background(Washi)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (himikoRes != 0) {
                    Image(
                        painter = painterResource(himikoRes),
                        contentDescription = "Химико",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text("👘", fontSize = 96.sp, modifier = Modifier.align(Alignment.Center))
                }

                TextButton(
                    onClick = onBackToMenu,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                ) {
                    Text("← Меню", color = Sumi)
                }

                TextButton(
                    onClick = { vm.newGame(s.difficulty) },
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp)
                ) {
                    Text("Новая", color = Sumi)
                }
            }

            SudokuBoard(
                state = s,
                onSelect = vm::select,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp)
            )

            ControlsPanel(
                state = s,
                onInput = vm::input,
                onNotes = vm::toggleNotes,
                onErase = vm::erase,
                onHint = vm::hint
            )
        }

        if (s.isWon) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(shape = RoundedCornerShape(18.dp), color = Washi) {
                    Column(
                        Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Готово!", fontSize = 32.sp, color = SakuraDeep)
                        Spacer(Modifier.height(8.dp))
                        Text("Ты решил судоку", color = Sumi)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { vm.newGame(s.difficulty) }) { Text("Ещё раз") }
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = onBackToMenu) { Text("В меню") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SudokuBoard(
    state: GameState,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(Washi)
            .border(2.dp, Sumi, RoundedCornerShape(10.dp))
    ) {
        for (r in 0..8) {
            Row(Modifier.weight(1f)) {
                for (c in 0..8) {
                    val idx = r * 9 + c
                    CellView(
                        cell = state.cells[idx],
                        selected = state.selected == idx,
                        mod = Modifier.weight(1f).fillMaxHeight()
                            .border(
                                if (r % 3 == 0 || c % 3 == 0) 1.dp else 0.5.dp,
                                Sumi.copy(alpha = 0.5f)
                            )
                            .clickable { onSelect(idx) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlsPanel(
    state: GameState,
    onInput: (Int) -> Unit,
    onNotes: () -> Unit,
    onErase: () -> Unit,
    onHint: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (n in 1..5) NumButton(n, onInput, Modifier.weight(1f))
            IconBtn(
                label = if (state.notesMode) "ON" else "✎",
                active = state.notesMode,
                onClick = onNotes,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (n in 6..9) NumButton(n, onInput, Modifier.weight(1f))
            IconBtn(label = "⌫", active = false, onClick = onErase, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = onHint,
            enabled = state.hintsLeft > 0,
            modifier = Modifier.fillMaxWidth(0.65f)
        ) {
            Text("Подсказка (${state.hintsLeft})", color = SakuraDeep)
        }
    }
}

@Composable
private fun NumButton(n: Int, onClick: (Int) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = Washi,
        shadowElevation = 2.dp,
        modifier = modifier.aspectRatio(1f).clickable { onClick(n) }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(n.toString(), fontSize = 22.sp, color = Sumi)
        }
    }
}

@Composable
private fun IconBtn(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = CircleShape,
        color = if (active) Sakura else Washi,
        shadowElevation = 2.dp,
        modifier = modifier.aspectRatio(1f).clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, fontSize = 18.sp, color = Sumi, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun CellView(cell: Cell, selected: Boolean, mod: Modifier) {
    val bg = when {
        selected -> Sakura.copy(alpha = 0.35f)
        cell.isFixed -> Washi
        else -> Color.White.copy(alpha = 0.5f)
    }
    Box(mod.background(bg), contentAlignment = Alignment.Center) {
        if (cell.value != 0) {
            Text(
                cell.value.toString(),
                fontSize = 18.sp,
                fontWeight = if (cell.isFixed) FontWeight.Bold else FontWeight.Normal,
                color = if (cell.isError) ErrorRed else Sumi
            )
        } else if (cell.notes.isNotEmpty()) {
            Column(Modifier.fillMaxSize()) {
                for (r in 0..2) Row(Modifier.weight(1f)) {
                    for (c in 0..2) {
                        val n = r * 3 + c + 1
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            if (n in cell.notes) Text(n.toString(), fontSize = 8.sp, color = Ai)
                        }
                    }
                }
            }
        }
    }
}
