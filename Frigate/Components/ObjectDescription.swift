import SwiftUI

/// A tracked object's description (written by GenAI or by hand) and Frigate's "Find similar",
/// as in Frigate's tracked object details. Shown only where it applies: the description when
/// there is one or GenAI describes the camera's objects, Find similar with semantic search on.
struct ObjectDescriptionSection: View {
    let serverID: UUID
    let event: FrigateEvent
    @Environment(AppModel.self) private var model
    @State private var text: String?
    @State private var role: String?
    @State private var editing = false
    @State private var draft = ""
    @State private var saving = false
    @State private var regenerating = false
    /// GenAI was asked for a new description; it arrives a little later.
    @State private var requested = false
    @State private var errorMessage: String?

    private var conn: ServerConnection? { model.connection(for: serverID) }
    private var camera: CameraConfig? { conn?.config?.cameras[event.camera] }
    private var isAdmin: Bool { (role ?? "admin") == "admin" }
    private var canSearch: Bool { conn?.config?.semanticSearch == true }
    private var describes: Bool { camera?.describesObjects == true }
    private var description: String { text ?? event.data?.description ?? "" }
    private var showsDescription: Bool { !description.isEmpty || describes || canSearch }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if canSearch {
                Button {
                    model.exploreSimilar = ExploreEntry(serverID: serverID, event: event)
                    model.tab = .explore
                } label: {
                    Label("Find similar", systemImage: "square.on.square")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Theme.text)
                        .frame(maxWidth: .infinity).frame(height: 44)
                        .card(border: Theme.controlBorder, radius: 12)
                }
                .buttonStyle(.plain)
                .padding(.bottom, 8)
            }
            if showsDescription {
                HStack(spacing: 14) {
                    SectionLabel(text: L("Description"))
                    if isAdmin {
                        Button { draft = description; editing = true } label: {
                            Image(systemName: "pencil").font(.system(size: 14, weight: .semibold))
                        }
                        .accessibilityLabel("Edit")
                        if describes && event.endTime != nil {
                            regenerateMenu
                        }
                    }
                }
                .foregroundStyle(Theme.textSecondary)
                .buttonStyle(.plain)
                Group {
                    if description.isEmpty && describes && event.endTime == nil {
                        Text("Frigate will not request a description from your Generative AI provider until the tracked object's lifecycle has ended.")
                            .foregroundStyle(Theme.textSecondary)
                    } else if requested {
                        HStack(spacing: 8) {
                            ProgressView().controlSize(.small)
                            Text("Waiting for the new description…").foregroundStyle(Theme.textSecondary)
                        }
                    } else {
                        Text(description.isEmpty ? L("None") : description)
                            .foregroundStyle(description.isEmpty ? Theme.textSecondary : Theme.text)
                            .textSelection(.enabled)
                    }
                }
                .font(.system(size: 14))
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 14).padding(.vertical, 12)
                .card(radius: 12)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 16)
        .sheet(isPresented: $editing) {
            DescriptionEditor(text: $draft, saving: saving) { Task { await save() } }
        }
        .alert("Something went wrong", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(errorMessage ?? "") }
        .task(id: serverID) { role = await conn?.client?.role() }
        // A new description: ask Frigate now and then until it is there (GenAI takes a while).
        .task(id: requested) {
            guard requested, let client = conn?.client else { return }
            let before = description
            for _ in 0..<20 {
                try? await Task.sleep(for: .seconds(3))
                guard !Task.isCancelled else { return }
                if let fresh = try? await client.event(id: event.id), let d = fresh.data?.description, d != before {
                    text = d
                    break
                }
            }
            requested = false
        }
    }

    private var regenerateMenu: some View {
        Menu {
            if event.hasSnapshot == true {
                Button("Regenerate from Snapshot") { Task { await regenerate(fromSnapshot: true) } }
            }
            Button("Regenerate from Thumbnails") { Task { await regenerate(fromSnapshot: false) } }
        } label: {
            Group {
                if regenerating { ProgressView().controlSize(.small) } else { Image(systemName: "sparkles") }
            }
            .font(.system(size: 14, weight: .semibold))
        }
        .disabled(regenerating || requested)
        .accessibilityLabel("Regenerate")
    }

    private func save() async {
        guard let client = conn?.client, !saving else { return }
        saving = true
        defer { saving = false }
        do {
            let new = draft.trimmingCharacters(in: .whitespacesAndNewlines)
            try await client.setDescription(eventID: event.id, new)
            text = new
            editing = false
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func regenerate(fromSnapshot: Bool) async {
        guard let client = conn?.client, !regenerating else { return }
        regenerating = true
        defer { regenerating = false }
        do {
            try await client.regenerateDescription(eventID: event.id, fromSnapshot: fromSnapshot)
            requested = true
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

/// Editing a description: a few lines of text, Cancel and Save.
private struct DescriptionEditor: View {
    @Binding var text: String
    let saving: Bool
    let save: () -> Void
    @Environment(\.dismiss) private var dismiss
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Description").font(.system(size: 17, weight: .semibold))
            TextEditor(text: $text)
                .focused($focused)
                .scrollContentBackground(.hidden)
                .font(.system(size: 15))
                .padding(.horizontal, 8).padding(.vertical, 6)
                .frame(minHeight: 120, maxHeight: 200)
                .background(Theme.background, in: RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(focused ? Theme.accent : Theme.controlBorder, lineWidth: 1))
                .overlay(alignment: .topLeading) {
                    if text.isEmpty {
                        Text("Description of the tracked object").font(.system(size: 15)).foregroundStyle(Theme.textSecondary)
                            .padding(.horizontal, 13).padding(.vertical, 14).allowsHitTesting(false)
                    }
                }
            HStack(spacing: 10) {
                Spacer()
                Button("Cancel") { closePresented(dismiss) }
                    .keyboardShortcut(.cancelAction)
                    .buttonStyle(PromptButtonStyle(primary: false))
                Button(action: save) {
                    HStack(spacing: 6) {
                        if saving { ProgressView().controlSize(.small).tint(Theme.onColor) }
                        Text("Save")
                    }
                }
                .buttonStyle(PromptButtonStyle(primary: true))
                .disabled(saving)
            }
        }
        .padding(20)
        .frame(maxWidth: 460)
        .background(Theme.card)
        .presentationBackground(Theme.card)
        .presentationDetents([.height(320)])
        .fittedSheet()
        .onAppear { focused = true }
    }
}
