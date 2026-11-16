import Foundation

// Frigate's face library (face recognition, 0.16+), called as Frigate's own Face Library page
// calls it. Recent recognition attempts live in the folder "train"; each name is a folder of
// face images. Attempt files are named like classification attempts:
// <event id>-<timestamp>-<name>-<score>.webp (see ClassificationAttempt).

extension FrigateClient {
    /// Every name with its images, and the recent attempts under "train".
    func faces() async throws -> [String: [String]] {
        try await get([String: [String]].self, "api/faces")
    }

    func faceImageURL(_ name: String, _ file: String) -> URL {
        url("clips/faces/\(name)/\(file)")
    }

    /// Adds a recent attempt to a name's images.
    func trainFace(_ file: String, as name: String) async throws {
        try await post("api/faces/train/\(name)/classify", json: ["training_file": file])
    }

    /// Runs recognition again on a recent attempt; returns the name and score it got.
    @discardableResult
    func reprocessFace(_ file: String) async throws -> (name: String, score: Double)? {
        let data = try await post("api/faces/reprocess", json: ["training_file": file])
        guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let name = obj["face_name"] as? String else { return nil }
        return (name, (obj["score"] as? NSNumber)?.doubleValue ?? 0)
    }

    /// Deletes images of a name, or recent attempts with `name` "train". Deleting every image of
    /// a name removes the name.
    func deleteFaces(_ name: String, files: [String]) async throws {
        try await post("api/faces/\(name)/delete", json: ["ids": files])
    }

    func renameFace(_ old: String, to new: String) async throws {
        try await sendJSON("PUT", "api/faces/\(old)/rename", json: ["new_name": new])
    }

    /// Moves one image of a name to another name.
    func reclassifyFace(_ file: String, from: String, to: String) async throws {
        try await post("api/faces/\(from)/reclassify", json: ["id": file, "new_name": to])
    }

    /// A new, empty name (Frigate turns spaces into underscores).
    func createFace(_ name: String) async throws {
        try await post("api/faces/\(name)/create", json: [String: Any]())
    }

    /// Uploads a picture of a person for a name; Frigate finds the face in it.
    func registerFace(_ name: String, jpeg: Data) async throws {
        let boundary = "tender-\(UUID().uuidString)"
        var body = Data()
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"file\"; filename=\"face.jpg\"\r\n".data(using: .utf8)!)
        body.append("Content-Type: image/jpeg\r\n\r\n".data(using: .utf8)!)
        body.append(jpeg)
        body.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)
        var req = URLRequest(url: url("api/faces/\(name)/register"))
        req.httpMethod = "POST"
        req.timeoutInterval = 60
        req.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        req.setValue("1", forHTTPHeaderField: "X-CSRF-TOKEN")
        req.httpBody = body
        let data = try await performPublic(req)
        // Frigate answers 200 with success false when it found no face in the picture.
        if let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any], obj["success"] as? Bool == false {
            throw FrigateError.http(400, obj["message"] as? String)
        }
    }
}
