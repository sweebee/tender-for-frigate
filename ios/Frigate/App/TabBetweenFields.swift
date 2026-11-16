import UIKit

// On the Mac, Tab does not move between SwiftUI text fields by itself. A hidden menu command
// for Tab and Shift-Tab moves to the next or previous field on screen, in reading order, in
// every form. It only applies while a one-line field is being typed in; elsewhere (the YAML
// editor) the key goes on as usual.

final class AppDelegate: UIResponder, UIApplicationDelegate {
    override func buildMenu(with builder: UIMenuBuilder) {
        super.buildMenu(with: builder)
        guard builder.system == .main else { return }
        let next = UIKeyCommand(title: "Next Field", action: #selector(nextField(_:)), input: "\t")
        let previous = UIKeyCommand(title: "Previous Field", action: #selector(previousField(_:)), input: "\t", modifierFlags: .shift)
        for command in [next, previous] {
            command.attributes = .hidden
            command.wantsPriorityOverSystemBehavior = true
        }
        builder.insertChild(UIMenu(options: .displayInline, children: [next, previous]), atEndOfMenu: .edit)
    }

    override func canPerformAction(_ action: Selector, withSender sender: Any?) -> Bool {
        if action == #selector(nextField(_:)) || action == #selector(previousField(_:)) {
            return Self.currentField() != nil
        }
        return super.canPerformAction(action, withSender: sender)
    }

    @objc func nextField(_ sender: Any?) { Self.move(by: 1) }
    @objc func previousField(_ sender: Any?) { Self.move(by: -1) }

    private static var windows: [UIWindow] {
        UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows)
    }

    private static func currentField() -> UITextField? {
        for window in windows where window.isKeyWindow {
            if let field = find(in: window, where: { $0.isFirstResponder }) { return field }
        }
        return nil
    }

    private static func move(by step: Int) {
        guard let current = currentField(), let window = current.window else { return }
        // The fields of the screen on top (a sheet), not those of the screen under it.
        var top = window.rootViewController
        while let presented = top?.presentedViewController, !presented.isBeingDismissed { top = presented }
        let root: UIView = top?.view.flatMap { current.isDescendant(of: $0) ? $0 : nil } ?? window
        var fields: [UITextField] = []
        collect(in: root, into: &fields)
        let ordered = fields.sorted {
            let a = $0.convert($0.bounds, to: window), b = $1.convert($1.bounds, to: window)
            return abs(a.midY - b.midY) > 4 ? a.midY < b.midY : a.minX < b.minX
        }
        guard let index = ordered.firstIndex(of: current), ordered.count > 1 else { return }
        let target = ordered[(index + step + ordered.count) % ordered.count]
        target.becomeFirstResponder()
        target.selectAll(nil)
    }

    private static func collect(in view: UIView, into fields: inout [UITextField]) {
        guard !view.isHidden, view.alpha > 0.01, view.isUserInteractionEnabled else { return }
        if let field = view as? UITextField, field.isEnabled, field.canBecomeFirstResponder { fields.append(field) }
        for sub in view.subviews { collect(in: sub, into: &fields) }
    }

    private static func find(in view: UIView, where test: (UITextField) -> Bool) -> UITextField? {
        if let field = view as? UITextField, test(field) { return field }
        for sub in view.subviews { if let f = find(in: sub, where: test) { return f } }
        return nil
    }
}
