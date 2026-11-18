import SwiftUI

/// A tile's size in grid cells, as Frigate's dashboard (LiveDashboardView) shapes its cameras:
/// wider than 2:1 takes two columns, standing (narrower than 1:1) two rows, the rest one 16:9 cell.
struct TileSpan: Hashable {
    var columns = 1
    var rows = 1
}

extension CameraConfig {
    var tileSpan: TileSpan {
        if aspectRatio > 2 { return TileSpan(columns: 2) }
        if aspectRatio < 1 { return TileSpan(rows: 2) }
        return TileSpan()
    }
}

/// Where a tile sits in the grid, in cells.
struct TileCell: Hashable {
    var row: Int
    var column: Int
    var span: TileSpan

    func moved(rows: Int) -> TileCell { TileCell(row: row + rows, column: column, span: span) }
}

enum TileGridPlacement {
    /// Places tiles in order the way a CSS grid does (row by row, never going back): each tile in
    /// the first free spot after the previous one where all its cells are free.
    static func place(_ spans: [TileSpan], columns: Int) -> [TileCell] {
        var taken = Set<[Int]>()
        var row = 0, column = 0
        return spans.map { wanted in
            let span = TileSpan(columns: min(wanted.columns, columns), rows: wanted.rows)
            while true {
                if column + span.columns > columns { row += 1; column = 0; continue }
                let free = (0..<span.rows).allSatisfy { r in
                    (0..<span.columns).allSatisfy { c in !taken.contains([row + r, column + c]) }
                }
                if free { break }
                column += 1
            }
            for r in 0..<span.rows { for c in 0..<span.columns { taken.insert([row + r, column + c]) } }
            defer { column += span.columns }
            return TileCell(row: row, column: column, span: span)
        }
    }

    /// The tiles (indices into `cells`) grouped in bands of rows that no tile crosses, so each
    /// band can be laid out on its own in a lazy stack.
    static func bands(_ cells: [TileCell]) -> [[Int]] {
        var bands: [[Int]] = []
        var end = -1
        for (i, cell) in cells.enumerated() {
            if cell.row > end || bands.isEmpty { bands.append([i]) } else { bands[bands.count - 1].append(i) }
            end = max(end, cell.row + cell.span.rows - 1)
        }
        return bands
    }
}

/// One band of the tile grid: rows of 16:9 cells (each with the tile's caption below the
/// picture), the tiles placed on their cells and stretched over their span.
struct TileBand: Layout {
    let columns: Int
    let spacing: CGFloat
    let rowSpacing: CGFloat
    /// Per subview, with rows counted from the band's top.
    let cells: [TileCell]

    private func metrics(_ width: CGFloat, _ subviews: Subviews) -> (cell: CGFloat, row: CGFloat) {
        let cell = max(0, (width - spacing * CGFloat(columns - 1)) / CGFloat(columns))
        // The caption under the picture: what a tile needs when its picture gets no height.
        let caption = subviews.first?.sizeThatFits(ProposedViewSize(width: cell, height: 0)).height ?? 0
        return (cell, cell * 9 / 16 + caption)
    }

    private var rows: Int { cells.map { $0.row + $0.span.rows }.max() ?? 0 }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? 360
        let m = metrics(width, subviews)
        return CGSize(width: width, height: CGFloat(rows) * m.row + CGFloat(max(rows - 1, 0)) * rowSpacing)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let m = metrics(bounds.width, subviews)
        for (sub, cell) in zip(subviews, cells) {
            let size = CGSize(width: CGFloat(cell.span.columns) * m.cell + CGFloat(cell.span.columns - 1) * spacing,
                              height: CGFloat(cell.span.rows) * m.row + CGFloat(cell.span.rows - 1) * rowSpacing)
            sub.place(at: CGPoint(x: bounds.minX + CGFloat(cell.column) * (m.cell + spacing),
                                  y: bounds.minY + CGFloat(cell.row) * (m.row + rowSpacing)),
                      proposal: ProposedViewSize(size))
        }
    }
}
