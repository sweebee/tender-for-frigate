import SwiftUI

// Asking for one line of text (a name, a password). iPhone and iPad use the system alert with a
// field; the Mac draws that alert as a stretched iOS dialog, so there it is a small sheet in the
// app's own style.

private let isMac = ProcessInfo.processInfo.isMacCatalystApp

extension View {
    func textPrompt(_ title: String, isPresented: Binding<Bool>, text: Binding<String>, placeholder: String,
                    secure: Bool = false, message: String? = nil, confirm: String,
                    action: @escaping () -> Void) -> some View {
        modifier(TextPromptModifier(title: title, isPresented: isPresented, text: text, placeholder: placeholder,
                                    secure: secure, message: message, confirm: confirm, action: action))
    }
}

private struct TextPromptModifier: ViewModifier {
    let title: String
    @Binding var isPresented: Bool
    @Binding var text: String
    let placeholder: String
    let secure: Bool
    let message: String?
    let confirm: String
    let action: () -> Void

    func body(content: Content) -> some View {
        if isMac {
            content.sheet(isPresented: $isPresented) {
                TextPromptSheet(title: title, text: $text, placeholder: placeholder, secure: secure, message: message,
                                confirm: confirm) { isPresented = false; action() }
            }
        } else {
            content.alert(title, isPresented: $isPresented) {
                if secure { SecureField(placeholder, text: $text) } else { TextField(placeholder, text: $text) }
                Button("Cancel", role: .cancel) {}
                Button(confirm, action: action)
            } message: {
                if let message { Text(message) }
            }
        }
    }
}

/// The Mac's version: title, the field, Cancel and the action; Return confirms, Esc cancels.
struct TextPromptSheet: View {
    let title: String
    @Binding var text: String
    let placeholder: String
    var secure = false
    var message: String?
    let confirm: String
    let action: () -> Void
    @Environment(\.dismiss) private var dismiss
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(title).font(.system(size: 17, weight: .semibold))
            if let message { Text(message).font(.system(size: 13)).foregroundStyle(Theme.textSecondary) }
            PromptField(placeholder: placeholder, text: $text, secure: secure, focused: $focused)
                .onSubmit { if !text.trimmingCharacters(in: .whitespaces).isEmpty { action() } }
            HStack(spacing: 10) {
                Spacer()
                Button("Cancel") { closePresented(dismiss) }
                    .keyboardShortcut(.cancelAction)
                    .buttonStyle(PromptButtonStyle(primary: false))
                Button(confirm, action: action)
                    .keyboardShortcut(.defaultAction)
                    .buttonStyle(PromptButtonStyle(primary: true))
                    .disabled(text.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(20)
        .frame(width: 380)
        .background(Theme.card)
        .presentationBackground(Theme.card)
        .fittedSheet()
        .onAppear { focused = true }
    }
}

/// A field as in the server connection screen: a rounded box, accent border while typing.
struct PromptField: View {
    let placeholder: String
    @Binding var text: String
    var secure = false
    var focused: FocusState<Bool>.Binding

    var body: some View {
        Group {
            if secure { RevealableField(text: $text, prompt: Text(placeholder).foregroundStyle(Theme.textSecondary)) }
            else { TextField("", text: $text, prompt: Text(placeholder).foregroundStyle(Theme.textSecondary)) }
        }
        .textInputAutocapitalization(.never)
        .autocorrectionDisabled()
        .focused(focused)
        .focusEffectDisabled()
        .font(.system(size: 15))
        .padding(.horizontal, 12)
        .frame(height: 40)
        .background(Theme.background, in: RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(focused.wrappedValue ? Theme.accent : Theme.controlBorder, lineWidth: 1))
    }
}

struct PromptButtonStyle: ButtonStyle {
    let primary: Bool
    @Environment(\.isEnabled) private var enabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(primary ? Theme.onColor : Theme.text)
            .padding(.horizontal, 16).frame(height: 34)
            .background(primary ? Theme.accent : Theme.control, in: RoundedRectangle(cornerRadius: 9))
            .overlay(RoundedRectangle(cornerRadius: 9).stroke(primary ? .clear : Theme.controlBorder, lineWidth: 1))
            .opacity(enabled ? (configuration.isPressed ? 0.8 : 1) : 0.45)
    }
}

extension View {
    /// A sheet as large as its content (iPad and Mac), instead of the standard form size.
    @ViewBuilder func fittedSheet() -> some View {
        if #available(iOS 18.0, macCatalyst 18.0, *) { presentationSizing(.fitted) } else { self }
    }
}

/// A password field with an eye button that shows what was typed.
struct RevealableField: View {
    @Binding var text: String
    var prompt: Text? = nil
    @State private var shown = false

    var body: some View {
        HStack(spacing: 8) {
            Group {
                if shown { TextField("", text: $text, prompt: prompt) } else { SecureField("", text: $text, prompt: prompt) }
            }
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            Button { shown.toggle() } label: {
                Image(systemName: shown ? "eye.slash" : "eye")
                    .font(.system(size: 14)).foregroundStyle(Theme.textSecondary)
                    .frame(width: 28, height: 28).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(shown ? L("Hide password") : L("Show password"))
        }
    }
}
