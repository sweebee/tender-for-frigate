import UIKit

/// iOS 27 crash on backspace in a multi-line text field: when deleting, the keyboard resets the
/// writing direction, and UIKit copies the typing attributes item by item
/// (-[UITextInputController _addToTypingAttributes:value:]). A colour bridged from SwiftUI
/// (`UIColor(Color)`, which SwiftUI's own text views use for `.foregroundStyle`) answers that copy
/// with nil, and the copy throws "attempt to insert nil object". Just before that call the typing
/// attributes' colours are made plain UIKit colours, for every text view in the app: the config
/// editor, and SwiftUI's TextEditor and vertical TextFields.
enum TextViewSafety {
    static func install() { _ = swizzle }

    private static let swizzle: Void = {
        let original = #selector(UITextView.setBaseWritingDirection(_:for:))
        let replacement = #selector(UITextView.tender_setBaseWritingDirection(_:for:))
        guard let a = class_getInstanceMethod(UITextView.self, original),
              let b = class_getInstanceMethod(UITextView.self, replacement) else { return }
        method_exchangeImplementations(a, b)
    }()
}

extension UITextView {
    @objc fileprivate func tender_setBaseWritingDirection(_ direction: NSWritingDirection, for range: UITextRange) {
        let attributes = typingAttributes
        if attributes.values.contains(where: { $0 is UIColor }) {
            typingAttributes = attributes.mapValues { value in
                guard let color = value as? UIColor else { return value }
                return UIColor(cgColor: color.resolvedColor(with: traitCollection).cgColor)
            }
        }
        // The implementations are exchanged: this calls UIKit's own.
        tender_setBaseWritingDirection(direction, for: range)
    }
}
