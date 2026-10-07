import Foundation

/// Cache local du dernier instantané synchronisé — offline-first : si le serveur est
/// injoignable, l'app continue d'afficher ce cache silencieusement, sans écran d'erreur.
@MainActor
final class SharedBlockageStore {
    private let fileManager = FileManager.default
    /// Seam de test : ne JAMAIS toucher aux vraies données de l'app.
    private let directoryOverride: URL?

    init(directoryOverride: URL? = nil) {
        self.directoryOverride = directoryOverride
    }

    private var fileURL: URL {
        if let directoryOverride { return directoryOverride.appendingPathComponent("shared-blockages.json") }
        let docs = fileManager.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return docs.appendingPathComponent("Sync/shared-blockages.json")
    }

    func load() -> [SharedBlockage] {
        guard let data = try? Data(contentsOf: fileURL),
              let decoded = try? SharedBlockageCoding.decoder.decode([SharedBlockage].self, from: data)
        else { return [] }
        return decoded.filter { !$0.isExpired }
    }

    func save(_ blockages: [SharedBlockage]) {
        let directory = fileURL.deletingLastPathComponent()
        if !fileManager.fileExists(atPath: directory.path) {
            try? fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        }
        guard let data = try? SharedBlockageCoding.encoder.encode(blockages.filter { !$0.isExpired }) else { return }
        try? data.write(to: fileURL)
    }
}
