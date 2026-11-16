import SwiftUI

/// Frigate's camera group icons: the Lucide set its web UI offers (names like "LuHouse"), drawn
/// from Resources/LucideIcons.json (made by scripts/lucide-icons.py from Frigate's web UI), so a
/// group looks the same in the app as in Frigate.
enum LucideIcons {
    /// Every icon's SVG path on a 24×24 grid, by Frigate's name.
    static let paths: [String: String] = {
        guard let url = Bundle.main.url(forResource: "LucideIcons", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let map = try? JSONDecoder().decode([String: String].self, from: data) else { return [:] }
        return map
    }()

    /// All names, A to Z.
    static let names: [String] = paths.keys.sorted()

    private static var cache: [String: Path] = [:]
    private static let lock = NSLock()

    /// The icon's outline on a 24×24 grid; nil for a name the set does not have.
    static func path(_ name: String) -> Path? {
        lock.lock(); defer { lock.unlock() }
        if let p = cache[name] { return p }
        guard let d = paths[name] else { return nil }
        let p = SVGPath.parse(d)
        cache[name] = p
        return p
    }

    /// "LuParkingSquare" → "parking square", for search and accessibility.
    static func words(_ name: String) -> String {
        var out = ""
        for ch in name.dropFirst(2) {
            if (ch.isUppercase || ch.isNumber) && !out.isEmpty && !(out.last?.isNumber == true && ch.isNumber) { out.append(" ") }
            out.append(ch)
        }
        return out.lowercased()
    }
}

/// One Lucide icon at a given size, stroked like Frigate's (2 of 24, round ends).
struct LucideIcon: View {
    let name: String
    var size: CGFloat = 16

    var body: some View {
        if let path = LucideIcons.path(name) {
            let scale = size / 24
            path
                .applying(CGAffineTransform(scaleX: scale, y: scale))
                .stroke(style: StrokeStyle(lineWidth: 2 * scale, lineCap: .round, lineJoin: .round))
                .frame(width: size, height: size)
                .accessibilityLabel(LucideIcons.words(name))
        } else {
            // Frigate's default ("generic") or a name from a newer icon set.
            Image(systemName: "square.grid.2x2").font(.system(size: size * 0.8, weight: .medium))
                .frame(width: size, height: size)
        }
    }
}

/// A small SVG path parser: M L H V C S Q T A Z, absolute and relative, as Lucide uses them.
enum SVGPath {
    static func parse(_ d: String) -> Path {
        var path = Path()
        var tokens = Tokenizer(d)
        var cmd: Character = "M"
        var cur = CGPoint.zero, start = CGPoint.zero
        var lastControl: CGPoint?
        var lastCmd: Character = " "

        while let next = tokens.peekCommand() ?? (tokens.hasNumber ? cmd : nil) {
            if tokens.peekCommand() != nil { tokens.skipCommand() }
            cmd = next
            let rel = cmd.isLowercase
            let base = rel ? cur : .zero
            func pt() -> CGPoint? {
                guard let x = tokens.number(), let y = tokens.number() else { return nil }
                return CGPoint(x: base.x + x, y: base.y + y)
            }
            switch cmd.uppercased().first! {
            case "M":
                guard let p = pt() else { return path }
                path.move(to: p); cur = p; start = p
                // Further pairs after a move are lines.
                cmd = rel ? "l" : "L"
                lastControl = nil
            case "L":
                guard let p = pt() else { return path }
                path.addLine(to: p); cur = p; lastControl = nil
            case "H":
                guard let x = tokens.number() else { return path }
                cur = CGPoint(x: rel ? cur.x + x : x, y: cur.y); path.addLine(to: cur); lastControl = nil
            case "V":
                guard let y = tokens.number() else { return path }
                cur = CGPoint(x: cur.x, y: rel ? cur.y + y : y); path.addLine(to: cur); lastControl = nil
            case "C":
                guard let c1 = pt(), let c2 = pt(), let p = pt() else { return path }
                path.addCurve(to: p, control1: c1, control2: c2); cur = p; lastControl = c2
            case "S":
                let c1 = reflect(lastControl, cur, if: "CS".contains(lastCmd.uppercased().first!))
                guard let c2 = pt(), let p = pt() else { return path }
                path.addCurve(to: p, control1: c1, control2: c2); cur = p; lastControl = c2
            case "Q":
                guard let c = pt(), let p = pt() else { return path }
                path.addQuadCurve(to: p, control: c); cur = p; lastControl = c
            case "T":
                let c = reflect(lastControl, cur, if: "QT".contains(lastCmd.uppercased().first!))
                guard let p = pt() else { return path }
                path.addQuadCurve(to: p, control: c); cur = p; lastControl = c
            case "A":
                guard let rx = tokens.number(), let ry = tokens.number(), let rot = tokens.number(),
                      let large = tokens.flag(), let sweep = tokens.flag(), let p = pt() else { return path }
                arc(&path, from: cur, to: p, rx: rx, ry: ry, rotation: rot, large: large, sweep: sweep)
                cur = p; lastControl = nil
            case "Z":
                path.closeSubpath(); cur = start; lastControl = nil
            default:
                return path
            }
            lastCmd = cmd
        }
        return path
    }

    private static func reflect(_ control: CGPoint?, _ cur: CGPoint, if ok: Bool) -> CGPoint {
        guard ok, let c = control else { return cur }
        return CGPoint(x: 2 * cur.x - c.x, y: 2 * cur.y - c.y)
    }

    /// An SVG elliptical arc as cubic curves (the endpoint-to-centre conversion of the SVG spec).
    private static func arc(_ path: inout Path, from p0: CGPoint, to p1: CGPoint, rx rxIn: CGFloat, ry ryIn: CGFloat,
                            rotation: CGFloat, large: Bool, sweep: Bool) {
        var rx = abs(rxIn), ry = abs(ryIn)
        guard rx > 0, ry > 0, p0 != p1 else { path.addLine(to: p1); return }
        let phi = rotation * .pi / 180
        let cosP = cos(phi), sinP = sin(phi)
        let dx = (p0.x - p1.x) / 2, dy = (p0.y - p1.y) / 2
        let x1 = cosP * dx + sinP * dy, y1 = -sinP * dx + cosP * dy
        let lambda = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry)
        if lambda > 1 { rx *= sqrt(lambda); ry *= sqrt(lambda) }
        let num = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1
        let den = rx * rx * y1 * y1 + ry * ry * x1 * x1
        var coef = den == 0 ? 0 : sqrt(max(0, num / den))
        if large == sweep { coef = -coef }
        let cx1 = coef * rx * y1 / ry, cy1 = -coef * ry * x1 / rx
        let cx = cosP * cx1 - sinP * cy1 + (p0.x + p1.x) / 2
        let cy = sinP * cx1 + cosP * cy1 + (p0.y + p1.y) / 2
        func angle(_ ux: CGFloat, _ uy: CGFloat, _ vx: CGFloat, _ vy: CGFloat) -> CGFloat {
            let a = atan2(ux * vy - uy * vx, ux * vx + uy * vy)
            return a
        }
        let theta1 = angle(1, 0, (x1 - cx1) / rx, (y1 - cy1) / ry)
        var delta = angle((x1 - cx1) / rx, (y1 - cy1) / ry, (-x1 - cx1) / rx, (-y1 - cy1) / ry)
        if !sweep && delta > 0 { delta -= 2 * .pi } else if sweep && delta < 0 { delta += 2 * .pi }
        let segments = max(1, Int(ceil(abs(delta) / (.pi / 2))))
        let step = delta / CGFloat(segments)
        let t = 4 / 3 * tan(step / 4)
        var a = theta1
        func point(_ ang: CGFloat) -> CGPoint {
            CGPoint(x: cx + rx * cos(ang) * cosP - ry * sin(ang) * sinP, y: cy + rx * cos(ang) * sinP + ry * sin(ang) * cosP)
        }
        func deriv(_ ang: CGFloat) -> CGPoint {
            CGPoint(x: -rx * sin(ang) * cosP - ry * cos(ang) * sinP, y: -rx * sin(ang) * sinP + ry * cos(ang) * cosP)
        }
        for i in 0..<segments {
            let b = a + step
            let pa = point(a), pb = i == segments - 1 ? p1 : point(b)
            let da = deriv(a), db = deriv(b)
            path.addCurve(to: pb, control1: CGPoint(x: pa.x + t * da.x, y: pa.y + t * da.y),
                          control2: CGPoint(x: pb.x - t * db.x, y: pb.y - t * db.y))
            a = b
        }
    }

    /// Reads commands, numbers (with SVG's compact forms like "1.5.5" and "-1-2") and arc flags.
    private struct Tokenizer {
        let chars: [Character]
        var i = 0
        init(_ s: String) { chars = Array(s) }

        mutating func skipSpace() {
            while i < chars.count, chars[i] == " " || chars[i] == "," || chars[i] == "\n" || chars[i] == "\t" { i += 1 }
        }
        mutating func peekCommand() -> Character? {
            skipSpace()
            guard i < chars.count, chars[i].isLetter, chars[i] != "e", chars[i] != "E" else { return nil }
            return chars[i]
        }
        mutating func skipCommand() { i += 1 }
        var hasNumber: Bool {
            mutating get {
                skipSpace()
                guard i < chars.count else { return false }
                let c = chars[i]
                return c.isNumber || c == "-" || c == "+" || c == "."
            }
        }
        mutating func number() -> CGFloat? {
            skipSpace()
            var s = ""
            var seenDot = false, seenExp = false
            if i < chars.count, chars[i] == "-" || chars[i] == "+" { s.append(chars[i]); i += 1 }
            while i < chars.count {
                let c = chars[i]
                if c.isNumber { s.append(c) }
                else if c == "." && !seenDot && !seenExp { seenDot = true; s.append(c) }
                else if (c == "e" || c == "E") && !seenExp { seenExp = true; s.append(c)
                    if i + 1 < chars.count, chars[i + 1] == "-" || chars[i + 1] == "+" { i += 1; s.append(chars[i]) }
                } else { break }
                i += 1
            }
            return Double(s).map { CGFloat($0) }
        }
        /// Arc flags are single digits and may run into the next number ("a1 1 0 011 1").
        mutating func flag() -> Bool? {
            skipSpace()
            guard i < chars.count, chars[i] == "0" || chars[i] == "1" else { return nil }
            defer { i += 1 }
            return chars[i] == "1"
        }
    }
}
