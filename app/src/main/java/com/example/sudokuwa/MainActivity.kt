package com.example.sudokuwa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.sin
import kotlin.random.Random

// ---------- Палитра ----------
private val Washi      = Color(0xFFF5EFE6)
private val Sumi       = Color(0xFF2B2B2B)
private val Sakura     = Color(0xFFF4B6C2)
private val SakuraDeep = Color(0xFFE38AA3)
private val Ai         = Color(0xFF3B5B7A)
private val ErrorRed   = Color(0xFFB94A48)
private val SkyTop     = Color(0xFFD6E4EF)
private val SkyBottom  = Color(0xFFF5EFE6)

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
    val dialogue: String = "Привет! Начнём?"
)

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
                Difficulty.EASY   -> "Не спеши. Всё уже решено — просто найди путь."
                Difficulty.MEDIUM -> "Покажи, на что ты способен."
                Difficulty.HARD   -> "Тишина. Только ты и числа. Я наблюдаю."
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
                it.copy(mistakes = it.mistakes + 1, dialogue = "Не спеши. Тени обманчивы.")
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
        _state.update { it.copy(hintsLeft = it.hintsLeft - 1, dialogue = "Смотри внимательно. Здесь — $correct.") }
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
            it.copy(isWon = true, dialogue = "Ты прошёл путь. Отдохни. Следующий будет труднее.")
        }
    }
}

// ---------- Activity ----------
class MainActivity : ComponentActivity() {
    private val vm: GameViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                Surface(Modifier.fillMaxSize(), color = Washi) { GameScreen(vm) }
            }
        }
    }
}

// ---------- UI ----------
@Composable
fun GameScreen(vm: GameViewModel) {
    val s by vm.state.collectAsState()
    val context = LocalContext.current

    val himikoRes = remember {
        context.resources.getIdentifier("himiko", "drawable", context.packageName)
    }

    Box(Modifier.fillMaxSize().background(Washi)) {
        Column(Modifier.fillMaxSize()) {

            // ---- ВЕРХ: персонаж ----
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(
                        Brush.verticalGradient(listOf(SkyTop, SkyBottom))
                    )
            ) {
                SakuraPetals(Modifier.fillMaxSize())

                if (himikoRes != 0) {
                    Image(
                        painter = painterResource(himikoRes),
                        contentDescription = "Химико",
                        modifier = Modifier
                            .fillMaxSize()
                            .align(Alignment.Center),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(
                        "👘",
                        fontSize = 96.sp,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                DialogueBubble(
                    text = s.dialogue,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )

                TextButton(
                    onClick = { vm.newGame(s.difficulty) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Text("Новая", color = Sumi)
                }
            }

            // ---- ЦЕНТР: поле судоку ----
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                SudokuBoard(state = s, onSelect = vm::select)
            }

            // ---- НИЗ: кнопки ----
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
                    }
                }
            }
        }
    }
}

@Composable
private fun SudokuBoard(state: GameState, onSelect: (Int) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
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
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (n in 1..5) NumButton(n, onInput)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (n in 6..9) NumButton(n, onInput)
            RoundButton(label = "⌫", active = false, onClick = onErase)
            RoundButton(label = if (state.notesMode) "✎ON" else "✎", active = state.notesMode, onClick = onNotes)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onHint,
            enabled = state.hintsLeft > 0,
            modifier = Modifier.fillMaxWidth(0.7f)
        ) {
            Text("Подсказка (${state.hintsLeft})")
        }
    }
}

@Composable
private fun NumButton(n: Int, onClick: (Int) -> Unit) {
    Surface(
        shape = CircleShape,
        color = Washi,
        shadowElevation = 2.dp,
        modifier = Modifier.size(52.dp).clickable { onClick(n) }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(n.toString(), fontSize = 22.sp, color = Sumi)
        }
    }
}

@Composable
private fun RoundButton(label: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = if (active) Sakura else Washi,
        shadowElevation = 2.dp,
        modifier = Modifier.size(52.dp).clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, fontSize = 16.sp, color = Sumi, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun DialogueBubble(text: String, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(text) {
        visible = false
        delay(60)
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 3 }),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Washi.copy(alpha = 0.92f),
            shadowElevation = 4.dp
        ) {
            Row(
                Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = text,
                    fontSize = 13.sp,
                    color = Sumi,
                    lineHeight = 17.sp
                )
            }
        }
    }
}

private data class Petal(
    val x0: Float,
    val y0: Float,
    val speed: Float,
    val sway: Float,
    val swaySpeed: Float,
    val size: Float,
    val alpha: Float
)

@Composable
private fun SakuraPetals(modifier: Modifier = Modifier) {
    val petals = remember {
        List(22) {
            Petal(
                x0 = Random.nextFloat(),
                y0 = Random.nextFloat(),
                speed = 0.04f + Random.nextFloat() * 0.07f,
                sway = 0.015f + Random.nextFloat() * 0.04f,
                swaySpeed = 0.5f + Random.nextFloat() * 1.2f,
                size = 4f + Random.nextFloat() * 6f,
                alpha = 0.35f + Random.nextFloat() * 0.5f
            )
        }
    }
    var time by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                time = (now - start) / 1_000_000_000f
            }
        }
    }

    Canvas(modifier) {
        petals.forEach { p ->
            val y = ((p.y0 + time * p.speed) % 1.2f) - 0.1f
            val x = p.x0 + sin(time * p.swaySpeed) * p.sway
            drawCircle(
                color = Sakura.copy(alpha = p.alpha),
                radius = p.size,
                center = Offset(x * size.width, y * size.height)
            )
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
                        Box(
                            Modifier.weight(1f).fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (n in cell.notes) Text(
                                n.toString(), fontSize = 8.sp, color = Ai
                            )
                        }
                    }
                }
            }
        }
    }
}
