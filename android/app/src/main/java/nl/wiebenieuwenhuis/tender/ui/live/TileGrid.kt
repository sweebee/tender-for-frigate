package nl.wiebenieuwenhuis.tender.ui.live

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import nl.wiebenieuwenhuis.tender.api.CameraConfig

/** A tile's size in grid cells, as Frigate's dashboard (LiveDashboardView) shapes its cameras:
 *  wider than 2:1 takes two columns, standing (narrower than 1:1) two rows, the rest one 16:9 cell. */
data class TileSpan(val columns: Int = 1, val rows: Int = 1)

val CameraConfig.tileSpan: TileSpan
    get() = when {
        aspectRatio > 2f -> TileSpan(columns = 2)
        aspectRatio < 1f -> TileSpan(rows = 2)
        else -> TileSpan()
    }

/** Where a tile sits in the grid, in cells. */
data class TileCell(val row: Int, val column: Int, val span: TileSpan)

/** Places tiles in order the way a CSS grid does (row by row, never going back): each tile in the
 *  first free spot after the previous one where all its cells are free. */
fun placeTiles(spans: List<TileSpan>, columns: Int): List<TileCell> {
    val taken = HashSet<Pair<Int, Int>>()
    var row = 0
    var column = 0
    return spans.map { wanted ->
        val span = TileSpan(minOf(wanted.columns, columns), wanted.rows)
        while (true) {
            if (column + span.columns > columns) { row++; column = 0; continue }
            val free = (0 until span.rows).all { r -> (0 until span.columns).all { c -> (row + r to column + c) !in taken } }
            if (free) break
            column++
        }
        for (r in 0 until span.rows) for (c in 0 until span.columns) taken += row + r to column + c
        TileCell(row, column, span).also { column += span.columns }
    }
}

/** The tiles (indices into `cells`) grouped in bands of rows that no tile crosses, so each band can
 *  be one item of the lazy grid. */
fun tileBands(cells: List<TileCell>): List<List<Int>> {
    val bands = mutableListOf<MutableList<Int>>()
    var end = -1
    cells.forEachIndexed { i, cell ->
        if (bands.isEmpty() || cell.row > end) bands += mutableListOf(i) else bands.last() += i
        end = maxOf(end, cell.row + cell.span.rows - 1)
    }
    return bands
}

/** One band of the tile grid: rows of 16:9 cells, each with room for the tile's caption below the
 *  picture, the tiles (one per cell in `cells`, rows counted from the band's top) stretched over
 *  their span. */
@Composable
fun TileBand(columns: Int, spacing: Dp, rowSpacing: Dp, cells: List<TileCell>, content: @Composable () -> Unit) {
    // The caption's height, as CameraTile draws it: one line of titleSmall under a 6 dp gap.
    val captionStyle = MaterialTheme.typography.titleSmall
    SubcomposeLayout { constraints ->
        val width = constraints.maxWidth
        val gap = spacing.roundToPx()
        val rowGap = rowSpacing.roundToPx()
        val cell = (width - gap * (columns - 1)).coerceAtLeast(0) / columns
        val caption = subcompose("caption") { Text("Ag", style = captionStyle, maxLines = 1) }
            .first().measure(Constraints()).height + 6.dp.roundToPx()
        val rowHeight = cell * 9 / 16 + caption
        val rows = cells.maxOfOrNull { it.row + it.span.rows } ?: 0
        val placeables = subcompose("tiles", content).zip(cells).map { (m, c) ->
            val w = c.span.columns * cell + (c.span.columns - 1) * gap
            val h = c.span.rows * rowHeight + (c.span.rows - 1) * rowGap
            m.measure(Constraints.fixed(w, h)) to c
        }
        layout(width, rows * rowHeight + (rows - 1).coerceAtLeast(0) * rowGap) {
            for ((p, c) in placeables) p.place(c.column * (cell + gap), c.row * (rowHeight + rowGap))
        }
    }
}
