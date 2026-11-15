import Foundation

// Frigate's custom classification (0.17+): models that put a tracked object into a class (an
// object model, e.g. which delivery service) or tell a fixed area's state (a state model, e.g.
// the gate open or closed). Called as Frigate's own Classification page calls them.

/// A model as it stands in the config (classification.custom.<name>).
struct ClassificationModel: Identifiable, Hashable {
    enum Kind: Hashable {
        /// The object label it looks at, and whether the class becomes a sub label or an attribute.
        case object(label: String, asAttribute: Bool)
        /// The cameras with their square area (0…1: x1, y1, x2, y2); runs on motion and/or every `interval` seconds.
        case state(cameras: [String: [Double]], motion: Bool, interval: Int?)
    }
    let serverID: UUID
    let name: String
    var enabled: Bool
    var kind: Kind
    var threshold: Double
    var saveAttempts: Int?

    var id: String { serverID.uuidString + "/" + name }
    var isState: Bool { if case .state = kind { return true }; return false }

    static func all(in config: [String: Any], serverID: UUID) -> [ClassificationModel] {
        let custom = (config["classification"] as? [String: Any])?["custom"] as? [String: Any] ?? [:]
        return custom.compactMap { key, raw -> ClassificationModel? in
            guard let m = raw as? [String: Any] else { return nil }
            let name = (m["name"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? key
            let kind: Kind
            if let o = m["object_config"] as? [String: Any] {
                kind = .object(label: (o["objects"] as? [String])?.first ?? "",
                               asAttribute: (o["classification_type"] as? String) == "attribute")
            } else if let s = m["state_config"] as? [String: Any] {
                let cams = (s["cameras"] as? [String: Any] ?? [:]).compactMapValues { c -> [Double]? in
                    ((c as? [String: Any])?["crop"] as? [NSNumber])?.map(\.doubleValue)
                }
                kind = .state(cameras: cams, motion: s["motion"] as? Bool ?? false, interval: (s["interval"] as? NSNumber)?.intValue)
            } else { return nil }
            return ClassificationModel(serverID: serverID, name: name, enabled: m["enabled"] as? Bool ?? true, kind: kind,
                                       threshold: (m["threshold"] as? NSNumber)?.doubleValue ?? 0.8,
                                       saveAttempts: (m["save_attempts"] as? NSNumber)?.intValue)
        }
        .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }
}

/// A model's labelled images, per class, and whether it needs training.
struct ClassificationDataset {
    var categories: [String: [String]] = [:]
    var hasTrained = false
    var lastTrained: Date?
    /// Images added or moved since the last training.
    var newImages = 0
    var totalImages = 0
    /// Something changed since the last training, so training again makes sense.
    var changed = false

    /// The classes in Frigate's order: by name, "none" last.
    var classNames: [String] {
        categories.keys.sorted { a, b in
            if a == "none" { return false }
            if b == "none" { return true }
            return a.localizedCaseInsensitiveCompare(b) == .orderedAscending
        }
    }
}

/// An image Frigate saved while classifying, waiting to be put in a class:
/// "<event id>-<timestamp>-<label>-<score>.webp" (state models use "none-none" as event id).
struct ClassificationAttempt: Identifiable, Hashable {
    let file: String
    let eventID: String
    let time: Date
    let label: String
    let score: Double
    var id: String { file }

    init?(file: String) {
        let base = file.replacingOccurrences(of: ".webp", with: "")
        let parts = base.split(separator: "-", omittingEmptySubsequences: false).map(String.init)
        guard parts.count >= 5, let ts = Double(parts[2]), let score = Double(parts[4]), !parts[3].isEmpty else { return nil }
        self.file = file
        eventID = parts[0] + "-" + parts[1]
        time = Date(timeIntervalSince1970: ts)
        label = parts[3]
        self.score = score
    }
}

extension FrigateClient {
    func classificationDataset(_ model: String) async throws -> ClassificationDataset {
        let data = try await data("api/classification/\(model)/dataset")
        guard let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { return ClassificationDataset() }
        var set = ClassificationDataset()
        set.categories = (obj["categories"] as? [String: Any] ?? [:]).compactMapValues { $0 as? [String] }
        if let meta = obj["training_metadata"] as? [String: Any] {
            set.hasTrained = meta["has_trained"] as? Bool ?? false
            if let s = meta["last_training_date"] as? String {
                set.lastTrained = ISO8601DateFormatter.frigate.date(from: s) ?? ISO8601DateFormatter().date(from: s)
                    ?? Self.localDate(s)
            }
            set.newImages = (meta["new_images_count"] as? NSNumber)?.intValue ?? 0
            set.totalImages = (meta["current_image_count"] as? NSNumber)?.intValue ?? 0
            set.changed = meta["dataset_changed"] as? Bool ?? false
        }
        return set
    }

    /// "2026-11-15T13:31:39.156140": the server's local time without a zone.
    static func localDate(_ s: String) -> Date? {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        for format in ["yyyy-MM-dd'T'HH:mm:ss.SSSSSS", "yyyy-MM-dd'T'HH:mm:ss"] {
            f.dateFormat = format
            if let d = f.date(from: s) { return d }
        }
        return nil
    }

    /// Recent attempts, newest first; the wizard's examples (example_000.jpg) are left out.
    func classificationAttempts(_ model: String) async throws -> [ClassificationAttempt] {
        try await rawClassificationTrainFiles(model).compactMap(ClassificationAttempt.init(file:)).sorted { $0.time > $1.time }
    }

    func rawClassificationTrainFiles(_ model: String) async throws -> [String] {
        let data = try await data("api/classification/\(model)/train")
        return (try JSONSerialization.jsonObject(with: data) as? [String]) ?? []
    }

    /// Puts a recent image into a class (made when it does not exist yet).
    func categorize(_ model: String, file: String, into category: String) async throws {
        try await post("api/classification/\(model)/dataset/categorize", json: ["training_file": file, "category": category])
    }

    /// Moves a labelled image to another class (Frigate 0.18).
    func reclassify(_ model: String, file: String, from category: String, to newCategory: String) async throws {
        try await post("api/classification/\(model)/dataset/\(category)/reclassify", json: ["id": file, "new_category": newCategory])
    }

    /// Deleting every image of a class deletes the class.
    func deleteDatasetImages(_ model: String, category: String, files: [String]) async throws {
        try await post("api/classification/\(model)/dataset/\(category)/delete", json: ["ids": files])
    }

    func deleteAttempts(_ model: String, files: [String]) async throws {
        try await post("api/classification/\(model)/train/delete", json: ["ids": files])
    }

    func createCategory(_ model: String, _ category: String) async throws {
        try await post("api/classification/\(model)/dataset/\(category)/create", json: [:])
    }

    func renameCategory(_ model: String, _ old: String, to new: String) async throws {
        try await sendJSON("PUT", "api/classification/\(model)/dataset/\(old)/rename", json: ["new_category": new])
    }

    /// Starts training; Frigate reports progress as the model's state on its websocket.
    func trainClassification(_ model: String) async throws {
        try await post("api/classification/\(model)/train", json: [:])
    }

    /// Removes the model's images and trained files, and then its config.
    func deleteClassificationModel(_ model: String) async throws {
        try await sendJSON("DELETE", "api/classification/\(model)", json: nil)
        try await setConfig(["classification": ["custom": [model: ""]]], requiresRestart: false,
                            updateTopic: "config/classification/custom/\(model)")
    }

    /// Fills the model's recent images with 24 varied examples from past events (object models)
    /// or recordings (state models). Takes a while; replaces what was there.
    func generateObjectExamples(_ model: String, label: String) async throws {
        _ = try await postLong("api/classification/generate_examples/object", json: ["model_name": model, "label": label])
    }

    func generateStateExamples(_ model: String, cameras: [String: [Double]]) async throws {
        _ = try await postLong("api/classification/generate_examples/state", json: ["model_name": model, "cameras": cameras])
    }

    func datasetImageURL(_ model: String, _ category: String, _ file: String) -> URL {
        url("clips/\(model)/dataset/\(category)/\(file)")
    }

    func attemptImageURL(_ model: String, _ file: String) -> URL {
        url("clips/\(model)/train/\(file)")
    }
}

extension ISO8601DateFormatter {
    /// Frigate's dates carry fractional seconds.
    static let frigate: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
}
