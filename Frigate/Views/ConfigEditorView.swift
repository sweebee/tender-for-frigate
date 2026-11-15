import SwiftUI
import UIKit

// The server's config file as text, as Frigate's Configuration Editor shows it: YAML with line
// numbers and colours, search (⌘F), and save with or without a restart. Frigate checks the text
// before it writes it; its message names the line, which the editor then marks.

struct ConfigEditorView: View {
    let serverID: UUID
    @Environment(AppModel.self) private var model
    @State private var original = ""
    @State private var text = ""
    @State private var loaded = false
    @State private var loadError: String?
    @State private var saving: SaveKind?
    @State private var problem: String?
    @State private var problemLine: Int?
    /// The line of Frigate's first complaint, for "Go to line".
    @State private var problemFirst: Int?
    @State private var notice: String?
    @State private var editor = ConfigEditorController()
    /// The keyboard is up: the text gets the room, saving moves to a small button in the header.
    @State private var typing = false

    enum SaveKind { case only, restart }

    private var conn: ServerConnection? { model.connection(for: serverID) }
    private var changed: Bool { loaded && text != original }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                ScreenTitle(text: L("Configuration"), showsBack: true)
                Spacer()
                if loaded {
                    Button { editor.find() } label: { HeaderIconLabel(systemName: "magnifyingglass") }
                        .accessibilityLabel("Search")
                        .keyboardShortcut("f")
                }
                if typing && (changed || problem != nil) { compactSave }
            }
            .padding(.horizontal, 16).padding(.top, 8).padding(.bottom, 6)

            if let loadError {
                Text(loadError).font(.system(size: 14)).foregroundStyle(Theme.textSecondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if !loaded {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                YAMLTextView(text: $text, errorLine: problemLine, controller: editor)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(Theme.cardBorder, lineWidth: 1))
                    .padding(.horizontal, 12)
            }

            if let notice {
                Label(notice, systemImage: "checkmark.circle.fill")
                    .font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.online)
                    .padding(.vertical, 8)
            }
            if typing, let problem {
                // One line of Frigate's refusal while typing; the whole message once the keyboard goes.
                Button { if let line = problemFirst { editor.go(toLine: line) } } label: {
                    Label(problem.components(separatedBy: "\n").first ?? problem, systemImage: "exclamationmark.triangle.fill")
                        .font(.system(size: 12, weight: .semibold)).foregroundStyle(Theme.destructive).lineLimit(1)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 16).padding(.vertical, 6)
            } else if !typing && (changed || problem != nil) {
                saveBar.padding(12)
            }
        }
        .animation(.easeOut(duration: 0.2), value: changed)
        .animation(.easeOut(duration: 0.2), value: typing)
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { note in
            // A Mac or iPad hardware keyboard reports a keyboard with no height: keep the full bar there.
            let frame = (note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect) ?? .zero
            typing = frame.height > 120
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in typing = false }
        .screenBackground()
        .toolbar(.hidden, for: .navigationBar)
        .task { await load() }
        .onChange(of: text) { _, _ in
            // Editing after a refusal: the marked line may no longer be the one Frigate meant.
            if problemLine != nil { problemLine = nil }
            notice = nil
        }
    }

    private var saveBar: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let problem {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(Theme.destructive)
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Frigate refused the configuration").font(.system(size: 14, weight: .semibold))
                        Text(problem).font(.system(size: 12).monospaced()).foregroundStyle(Theme.textSecondary)
                            .lineLimit(6)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .textSelection(.enabled)
                        if let line = problemFirst {
                            Button { problemLine = line; editor.go(toLine: line) } label: {
                                Text(L("Go to line \(line)")).font(.system(size: 13, weight: .semibold))
                            }
                            .buttonStyle(.plain).foregroundStyle(Theme.accent)
                        }
                    }
                }
            } else {
                Text("Changed").font(.system(size: 14, weight: .semibold))
                Text("Frigate checks the configuration before it saves it. Most changes apply after a restart.")
                    .font(.system(size: 12)).foregroundStyle(Theme.textSecondary)
            }
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) { undoButton; saveOnlyButton; restartButton }
                VStack(spacing: 8) {
                    HStack(spacing: 8) { undoButton; saveOnlyButton }
                    restartButton
                }
            }
        }
        .padding(14)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(problem == nil ? Theme.controlBorder : Theme.destructive.opacity(0.6), lineWidth: 1))
        .readableWidth()
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }

    /// Saving while the keyboard is up: one button, its choices in a menu.
    private var compactSave: some View {
        Menu {
            Button { Task { await save(.restart) } } label: { Label("Save and restart", systemImage: "arrow.clockwise") }
            Button { Task { await save(.only) } } label: { Label("Save only", systemImage: "square.and.arrow.down") }
            Divider()
            Button(role: .destructive) {
                text = original; problem = nil; problemLine = nil; editor.reload(original)
            } label: { Label("Undo", systemImage: "arrow.uturn.backward") }
        } label: {
            Group { if saving != nil { ProgressView().tint(Theme.onColor) } else { Text("Save") } }
                .font(.system(size: 14, weight: .bold)).foregroundStyle(Theme.onColor)
                .padding(.horizontal, 14).frame(height: 36)
                .background(Theme.accent, in: Capsule())
        }
        .disabled(!changed || saving != nil)
    }

    private var undoButton: some View {
        barButton(L("Undo"), primary: false, busy: false) {
            text = original
            problem = nil
            problemLine = nil
            editor.reload(original)
        }
        .disabled(!changed)
    }

    private var saveOnlyButton: some View {
        barButton(L("Save only"), primary: false, busy: saving == .only) { Task { await save(.only) } }
            .disabled(!changed)
    }

    private var restartButton: some View {
        barButton(L("Save and restart"), primary: true, busy: saving == .restart) { Task { await save(.restart) } }
            .keyboardShortcut("s")
            .disabled(!changed)
    }

    private func barButton(_ title: String, primary: Bool, busy: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Group { if busy { ProgressView().tint(primary ? Theme.onColor : Theme.text) } else { Text(title).lineLimit(1) } }
                .font(.system(size: 14, weight: primary ? .bold : .semibold))
                .foregroundStyle(primary ? Theme.onColor : Theme.text)
                .frame(maxWidth: .infinity, minHeight: 36)
                .padding(.horizontal, 10)
                .background(primary ? Theme.accent : Theme.control, in: RoundedRectangle(cornerRadius: 10))
        }
        .buttonStyle(.plain)
        .disabled(saving != nil)
    }

    /// Frigate's "Line 43: cameras -> carport -> detect -> heigth - …" names the line of the block the
    /// wrong key sits in (from 0). Point each at the key itself, counted from 1 as the editor shows them.
    static func resolveLines(in message: String, text: String) -> (message: String, first: Int?) {
        let lines = text.components(separatedBy: "\n")
        var first: Int?
        let rows = message.components(separatedBy: "\n").map { row -> String in
            guard let m = row.firstMatch(of: #/^Line (\d+): (.+?) - /#), let start = Int(m.1) else { return row }
            let key = String(m.2).components(separatedBy: " -> ").last ?? ""
            var line = start + 1
            if !key.isEmpty, Int(key) == nil {
                let pattern = "^\\s*-?\\s*" + NSRegularExpression.escapedPattern(for: key) + "\\s*:"
                if let re = try? NSRegularExpression(pattern: pattern),
                   let found = lines.indices.dropFirst(start).prefix(400).first(where: { i in
                       re.firstMatch(in: lines[i], range: NSRange(location: 0, length: (lines[i] as NSString).length)) != nil
                   }) {
                    line = found + 1
                }
            }
            if first == nil { first = line }
            return row.replacingCharacters(in: row.range(of: "Line \(start):")!, with: L("Line \(line):"))
        }
        return (rows.joined(separator: "\n"), first)
    }

    private func load() async {
        guard let client = conn?.client else { return }
        do {
            let t = try await client.rawConfigText()
            original = t
            text = t
            loaded = true
        } catch FrigateError.unauthorized {
            loadError = L("Only an admin can read the configuration.")
        } catch {
            loadError = error.localizedDescription
        }
    }

    private func save(_ kind: SaveKind) async {
        guard let conn, let client = conn.client else { return }
        saving = kind
        defer { saving = nil }
        do {
            try await client.saveRawConfig(text, restart: kind == .restart)
            original = text
            problem = nil
            problemLine = nil
            notice = kind == .restart ? L("Saved; Frigate is restarting") : L("Saved; restart Frigate to apply it")
            if kind == .restart {
                // Back once Frigate answers again.
                Task {
                    try? await Task.sleep(for: .seconds(20))
                    await conn.connect()
                }
            }
        } catch FrigateError.http(_, let detail) {
            let message = (detail ?? L("Frigate did not save it."))
                .replacingOccurrences(of: "Your configuration is invalid.\nSee the official documentation at docs.frigate.video.\n\n", with: "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            let resolved = Self.resolveLines(in: message, text: text)
            problem = resolved.message
            problemFirst = resolved.first
            if let line = resolved.first {
                problemLine = line
                editor.go(toLine: line)
            }
        } catch {
            problem = error.localizedDescription
        }
    }
}

// MARK: - The text view

/// Lets the SwiftUI screen drive the text view: search, jump to a line, reset its text.
@MainActor
final class ConfigEditorController {
    weak var textView: YAMLTextView.LinedTextView?

    func find() {
        guard let tv = textView else { return }
        tv.becomeFirstResponder()
        tv.findInteraction?.presentFindNavigator(showingReplace: false)
    }

    func go(toLine line: Int) {
        guard let tv = textView else { return }
        let ns = tv.text as NSString
        var index = 0, current = 1
        while current < line, index < ns.length {
            let r = ns.lineRange(for: NSRange(location: index, length: 0))
            index = NSMaxRange(r)
            current += 1
        }
        let range = ns.lineRange(for: NSRange(location: min(index, ns.length), length: 0))
        tv.scrollRangeToVisible(range)
        tv.selectedRange = NSRange(location: range.location, length: 0)
        tv.becomeFirstResponder()
    }

    func reload(_ text: String) {
        textView?.setCode(text)
    }
}

struct YAMLTextView: UIViewRepresentable {
    @Binding var text: String
    var errorLine: Int?
    let controller: ConfigEditorController

    func makeUIView(context: Context) -> LinedTextView {
        // TextKit 1: the line numbers are drawn from its layout manager.
        let tv = LinedTextView(usingTextLayoutManager: false)
        tv.delegate = context.coordinator
        tv.backgroundColor = UIColor(Theme.card)
        tv.tintColor = UIColor(Theme.accent)
        tv.autocorrectionType = .no
        tv.autocapitalizationType = .none
        tv.smartQuotesType = .no
        tv.smartDashesType = .no
        tv.smartInsertDeleteType = .no
        tv.spellCheckingType = .no
        tv.keyboardType = .asciiCapable
        tv.isFindInteractionEnabled = true
        tv.alwaysBounceVertical = true
        tv.keyboardDismissMode = .interactive
        tv.textContainerInset = UIEdgeInsets(top: 10, left: LinedTextView.gutter + 8, bottom: 40, right: 10)
        // The keyboard asks for the font at the caret: without one set here UIKit crashes on a nil value.
        tv.font = UIFont.monospacedSystemFont(ofSize: 13, weight: .regular)
        tv.textColor = UIColor(Theme.text)
        tv.setCode(text)
        controller.textView = tv
        return tv
    }

    func updateUIView(_ tv: LinedTextView, context: Context) {
        if tv.errorLine != errorLine {
            tv.errorLine = errorLine
            tv.highlight()
        }
        if tv.text != text { tv.setCode(text) }
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UITextViewDelegate {
        var parent: YAMLTextView
        init(_ parent: YAMLTextView) { self.parent = parent }

        func textViewDidChange(_ tv: UITextView) {
            (tv as? LinedTextView)?.highlight()
            parent.text = tv.text
        }

        /// A new line keeps the indentation of the one above, as in any code editor.
        func textView(_ tv: UITextView, shouldChangeTextIn range: NSRange, replacementText string: String) -> Bool {
            guard string == "\n" else { return true }
            let ns = tv.text as NSString
            let line = ns.substring(with: ns.lineRange(for: NSRange(location: range.location, length: 0)))
            var indent = String(line.prefix { $0 == " " })
            if line.trimmingCharacters(in: .whitespacesAndNewlines).hasSuffix(":") { indent += "  " }
            guard !indent.isEmpty else { return true }
            guard let start = tv.position(from: tv.beginningOfDocument, offset: range.location),
                  let end = tv.position(from: start, offset: range.length),
                  let textRange = tv.textRange(from: start, to: end) else { return true }
            tv.replace(textRange, withText: "\n" + indent)
            return false
        }
    }

    /// A text view with line numbers in a gutter on the left and YAML colours.
    final class LinedTextView: UITextView {
        static let gutter: CGFloat = 40
        var errorLine: Int?
        private let codeFont = UIFont.monospacedSystemFont(ofSize: 13, weight: .regular)

        override func draw(_ rect: CGRect) {
            super.draw(rect)
            let lm = layoutManager
            let ns = text as NSString
            let attrs: [NSAttributedString.Key: Any] = [
                .font: UIFont.monospacedDigitSystemFont(ofSize: 11, weight: .regular),
                .foregroundColor: UIColor(Theme.textSecondary).withAlphaComponent(0.6),
            ]
            UIColor(Theme.control).setFill()
            UIRectFill(CGRect(x: 0, y: rect.minY, width: Self.gutter, height: rect.height))
            var lineNumber = 1
            var index = 0
            let glyphsInView = lm.glyphRange(forBoundingRect: rect.offsetBy(dx: 0, dy: -textContainerInset.top), in: textContainer)
            let firstChar = lm.characterIndexForGlyph(at: glyphsInView.location)
            // Count the lines above what is drawn, then number the visible ones.
            if firstChar > 0 {
                lineNumber += ns.substring(to: firstChar).reduce(0) { $1 == "\n" ? $0 + 1 : $0 }
                index = ns.lineRange(for: NSRange(location: firstChar, length: 0)).location
            }
            while index < ns.length || (index == ns.length && (ns.length == 0 || ns.hasSuffix("\n"))) {
                let lineRange = ns.lineRange(for: NSRange(location: index, length: 0))
                let glyph = lm.glyphIndexForCharacter(at: min(index, max(ns.length - 1, 0)))
                var frag = lm.lineFragmentRect(forGlyphAt: glyph, effectiveRange: nil)
                if index == ns.length { frag = lm.extraLineFragmentRect }
                let y = frag.minY + textContainerInset.top
                if y > rect.maxY { break }
                let label = "\(lineNumber)" as NSString
                let size = label.size(withAttributes: attrs)
                label.draw(at: CGPoint(x: Self.gutter - 6 - size.width, y: y + (frag.height - size.height) / 2), withAttributes: attrs)
                lineNumber += 1
                if NSMaxRange(lineRange) == index { break }
                index = NSMaxRange(lineRange)
            }
        }

        /// The keyboard asks this for the caret's font; UIKit's own answer comes up nil for this text
        /// view and crashes ("attempt to insert nil object"), so answer it here.
        override func textStyling(at position: UITextPosition, in direction: UITextStorageDirection) -> [NSAttributedString.Key: Any]? {
            [.font: codeFont, .foregroundColor: UIColor(Theme.text)]
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            setNeedsDisplay()
        }

        /// Replaces all text through the storage, with the code font: UITextView's own `text` setter asks
        /// UIKit for default attributes that come up nil for this view and crash.
        func setCode(_ string: String) {
            let attrs: [NSAttributedString.Key: Any] = [.font: codeFont, .foregroundColor: UIColor(Theme.text)]
            textStorage.beginEditing()
            textStorage.replaceCharacters(in: NSRange(location: 0, length: textStorage.length),
                                          with: NSAttributedString(string: string, attributes: attrs))
            textStorage.endEditing()
            highlight()
        }

        /// Colours: keys, comments, quoted text, numbers and true/false; the line Frigate refused.
        func highlight() {
            let ns = text as NSString
            let full = NSRange(location: 0, length: ns.length)
            let selected = selectedRange
            let storage = textStorage
            storage.beginEditing()
            storage.setAttributes([.font: codeFont, .foregroundColor: UIColor(Theme.text)], range: full)
            func paint(_ pattern: String, _ color: UIColor, group: Int = 0, options: NSRegularExpression.Options = [.anchorsMatchLines]) {
                guard let re = try? NSRegularExpression(pattern: pattern, options: options) else { return }
                for m in re.matches(in: text, range: full) {
                    let r = m.range(at: group)
                    if r.location != NSNotFound { storage.addAttribute(.foregroundColor, value: color, range: r) }
                }
            }
            paint(#":\s*(-?\d+(\.\d+)?)\s*$"#, UIColor(Theme.warning), group: 1)
            paint(#"(?:^|:\s)\s*(true|false|null)\s*$"#, UIColor(Theme.warning), group: 1)
            paint(#"^\s*-?\s*([A-Za-z0-9_\-. ]+?):(?=\s|$)"#, UIColor(Theme.accent), group: 1)
            paint(#"(["'])(?:(?!\1).)*\1"#, UIColor(Theme.online))
            paint(#"(^|\s)#.*$"#, UIColor(Theme.textSecondary).withAlphaComponent(0.7))
            if let errorLine {
                var index = 0, current = 1
                while current < errorLine, index < ns.length {
                    index = NSMaxRange(ns.lineRange(for: NSRange(location: index, length: 0)))
                    current += 1
                }
                if index < ns.length {
                    storage.addAttribute(.backgroundColor, value: UIColor(Theme.destructive).withAlphaComponent(0.25),
                                         range: ns.lineRange(for: NSRange(location: index, length: 0)))
                }
            }
            storage.endEditing()
            selectedRange = selected
            typingAttributes = [.font: codeFont, .foregroundColor: UIColor(Theme.text)]
            setNeedsDisplay()
        }
    }
}
