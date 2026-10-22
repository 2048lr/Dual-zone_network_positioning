import SwiftUI
import UIKit
import AVFoundation
import Combine
import Shared

/// SSTV 接收音频：录任意时长，停止后重采样到共享内核要求的采样率。
final class SstvAudio: ObservableObject {

    @Published private(set) var isRecording = false
    @Published private(set) var elapsed: TimeInterval = 0

    private let engine = AVAudioEngine()
    private var samples: [Float] = []
    private var inputRate: Double = 48_000
    private var timer: Timer?
    private var startTime = Date()

    func start() {
        guard !isRecording else { return }
        samples = []
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
            try session.setActive(true)
        } catch {
            return
        }
        let input = engine.inputNode
        let format = input.inputFormat(forBus: 0)
        inputRate = format.sampleRate > 0 ? format.sampleRate : 48_000
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 4096, format: format) { [weak self] buffer, _ in
            guard let self, let channel = buffer.floatChannelData?[0] else { return }
            let frames = Int(buffer.frameLength)
            self.samples.append(contentsOf: UnsafeBufferPointer(start: channel, count: frames))
        }
        engine.prepare()
        do {
            try engine.start()
        } catch {
            input.removeTap(onBus: 0)
            return
        }
        isRecording = true
        startTime = Date()
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            guard let self else { return }
            self.elapsed = Date().timeIntervalSince(self.startTime)
        }
    }

    /// 停止并返回目标采样率的 PCM16 数据。
    func stop() -> Data {
        timer?.invalidate()
        timer = nil
        engine.inputNode.removeTap(onBus: 0)
        if engine.isRunning { engine.stop() }
        isRecording = false

        let target = Double(SstvBridge.shared.sampleRate)
        let resampled = Self.resample(samples, from: inputRate, to: target)
        samples = []
        elapsed = 0

        var data = Data(capacity: resampled.count * 2)
        for sample in resampled {
            let clamped = max(-1.0, min(1.0, sample))
            let v = Int16(clamped * 32767.0)
            withUnsafeBytes(of: v.littleEndian) { data.append(contentsOf: $0) }
        }
        return data
    }

    func cancel() {
        timer?.invalidate()
        timer = nil
        engine.inputNode.removeTap(onBus: 0)
        if engine.isRunning { engine.stop() }
        isRecording = false
        samples = []
        elapsed = 0
    }

    private static func resample(_ input: [Float], from: Double, to: Double) -> [Float] {
        guard !input.isEmpty, from > 0, to > 0 else { return [] }
        if abs(from - to) < 1 { return input }
        let ratio = from / to
        let outCount = Int(Double(input.count) / ratio)
        var out = [Float](repeating: 0, count: outCount)
        for i in 0..<outCount {
            let pos = Double(i) * ratio
            let index = Int(pos)
            let frac = Float(pos - Double(index))
            let a = input[min(index, input.count - 1)]
            let b = input[min(index + 1, input.count - 1)]
            out[i] = a + (b - a) * frac
        }
        return out
    }
}

/// SSTV 图库：图片存 Documents/SSTV，JSON 索引元数据。
struct SstvImage: Identifiable, Codable {
    let id: UUID
    let fileName: String
    let modeName: String
    let receivedAt: Date
    let width: Int
    let height: Int
}

final class SstvStore: ObservableObject {

    static let shared = SstvStore()

    @Published private(set) var images: [SstvImage] = []
    @Published var forcedVis: Int = 0
    @Published var autoSave: Bool = true

    private let dir: URL
    private let indexURL: URL

    private init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        dir = docs.appendingPathComponent("SSTV", isDirectory: true)
        indexURL = dir.appendingPathComponent("index.json")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        load()
    }

    func modes() -> [SstvModeInfo] {
        SstvBridge.shared.modes()
    }

    /// 解码 PCM 数据为图片；失败返回错误文本。
    func decode(data: Data) -> Result<SstvImage, String> {
        let bytes = KotlinByteArray(size: Int32(data.count))
        for (index, byte) in data.enumerated() {
            bytes.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        let outcome = SstvBridge.shared.decodePcm(bytes: bytes, forcedVis: Int32(forcedVis))
        guard outcome.success else {
            return .failure(outcome.error.isEmpty ? "解码失败" : outcome.error)
        }
        guard let image = Self.makeImage(
            pixels: outcome.pixels,
            width: Int(outcome.width),
            height: Int(outcome.height)
        ) else {
            return .failure("无法生成图像")
        }
        guard autoSave else { return .success(SstvImage(
            id: UUID(), fileName: "", modeName: outcome.modeName,
            receivedAt: Date(), width: Int(outcome.width), height: Int(outcome.height)
        )) }

        let id = UUID()
        let fileName = "\(id.uuidString).png"
        let fileURL = dir.appendingPathComponent(fileName)
        guard let png = image.pngData(), (try? png.write(to: fileURL)) != nil else {
            return .failure("保存图片失败")
        }
        let record = SstvImage(
            id: id,
            fileName: fileName,
            modeName: outcome.modeName,
            receivedAt: Date(),
            width: Int(outcome.width),
            height: Int(outcome.height)
        )
        images.insert(record, at: 0)
        saveIndex()
        return .success(record)
    }

    func image(for record: SstvImage) -> UIImage? {
        UIImage(contentsOfFile: dir.appendingPathComponent(record.fileName).path)
    }

    func delete(_ record: SstvImage) {
        try? FileManager.default.removeItem(at: dir.appendingPathComponent(record.fileName))
        images.removeAll { $0.id == record.id }
        saveIndex()
    }

    private func load() {
        guard let data = try? Data(contentsOf: indexURL),
              let decoded = try? JSONDecoder().decode([SstvImage].self, from: data) else { return }
        images = decoded
    }

    private func saveIndex() {
        guard let data = try? JSONEncoder().encode(images) else { return }
        try? data.write(to: indexURL, options: .atomic)
    }

    private static func makeImage(pixels: KotlinIntArray, width: Int, height: Int) -> UIImage? {
        guard width > 0, height > 0 else { return nil }
        var rgba = [UInt8](repeating: 0, count: width * height * 4)
        for i in 0..<(width * height) {
            let rgb = pixels.get(index: Int32(i))
            rgba[i * 4] = UInt8((rgb shr 16) and 0xFF)
            rgba[i * 4 + 1] = UInt8((rgb shr 8) and 0xFF)
            rgba[i * 4 + 2] = UInt8(rgb and 0xFF)
            rgba[i * 4 + 3] = 255
        }
        guard let provider = CGDataProvider(data: Data(rgba) as CFData),
              let cgImage = CGImage(
                width: width,
                height: height,
                bitsPerComponent: 8,
                bitsPerPixel: 32,
                bytesPerRow: width * 4,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
                provider: provider,
                decode: nil,
                shouldInterpolate: false,
                intent: .defaultIntent
              ) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}

/// SSTV 主页面：选择模式 → 开始/停止接收 → 解码入图库。
struct SstvMainView: View {

    @ObservedObject var store = SstvStore.shared
    @StateObject private var audio = SstvAudio()

    var presetModeName: String?
    var autoStart: Bool

    @State private var status = "空闲"
    @State private var lastImage: UIImage?

    var body: some View {
        List {
            Section("接收") {
                Picker("模式", selection: $store.forcedVis) {
                    Text("自动识别（VIS）").tag(0)
                    ForEach(store.modes(), id: \.visCode) { mode in
                        Text("\(mode.name) · \(mode.width)×\(mode.height)").tag(Int(mode.visCode))
                    }
                }
                Button(audio.isRecording ? "停止并解码" : "开始接收") {
                    if audio.isRecording {
                        let data = audio.stop()
                        status = "解码中…"
                        DispatchQueue.global(qos: .userInitiated).async {
                            let result = store.decode(data: data)
                            DispatchQueue.main.async {
                                switch result {
                                case .success(let image):
                                    status = "解码成功：\(image.modeName) \(image.width)×\(image.height)"
                                    lastImage = store.image(for: image)
                                case .failure(let error):
                                    status = "解码失败：\(error)"
                                }
                            }
                        }
                    } else {
                        audio.start()
                        status = "正在接收…"
                    }
                }
                if audio.isRecording {
                    Text(String(format: "已录 %.1f 秒", audio.elapsed))
                        .font(.footnote.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                Text(status).font(.footnote).foregroundStyle(.secondary)
            }

            if let lastImage {
                Section("最近解码") {
                    Image(uiImage: lastImage)
                        .resizable()
                        .scaledToFit()
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                }
            }

            Section("图库") {
                NavigationLink(value: Route.sstvGallery(pickForTx: false)) {
                    LabeledContent("已保存图片", value: "\(store.images.count)")
                }
                NavigationLink(value: Route.sstvSettings) {
                    Label("SSTV 设置", systemImage: "gearshape")
                }
            }

            Section {
                NavigationLink(value: Route.sstvTx) {
                    Label("发射", systemImage: "waveform")
                }
            } footer: {
                Text("发射需从相册选取图片并编码为 SSTV 音频；当前版本先完成接收与图库，发射在后续提交补齐。")
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("SSTV")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if let presetModeName,
               let match = store.modes().first(where: { $0.name == presetModeName }) {
                store.forcedVis = Int(match.visCode)
            }
            if autoStart && !audio.isRecording {
                audio.start()
                status = "正在接收…"
            }
        }
        .onDisappear { audio.cancel() }
    }
}

/// SSTV 图库页。
struct SstvGalleryView: View {

    @ObservedObject var store = SstvStore.shared
    var pickForTx: Bool

    private let columns = [GridItem(.adaptive(minimum: 140), spacing: 8)]

    var body: some View {
        Group {
            if store.images.isEmpty {
                Text("暂无 SSTV 图片").foregroundStyle(.secondary)
            } else {
                ScrollView {
                    LazyVGrid(columns: columns, spacing: 8) {
                        ForEach(store.images) { record in
                            NavigationLink {
                                SstvImageDetailView(record: record)
                            } label: {
                                VStack(alignment: .leading, spacing: 4) {
                                    if let image = store.image(for: record) {
                                        Image(uiImage: image)
                                            .resizable()
                                            .scaledToFill()
                                            .frame(height: 110)
                                            .clipped()
                                            .clipShape(RoundedRectangle(cornerRadius: 8))
                                    }
                                    Text(record.modeName).font(.caption)
                                    Text(record.receivedAt, style: .date)
                                        .font(.caption2)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                    .padding()
                }
            }
        }
        .navigationTitle("SSTV 图库")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct SstvImageDetailView: View {
    @ObservedObject var store = SstvStore.shared
    let record: SstvImage

    var body: some View {
        Group {
            if let image = store.image(for: record) {
                ScrollView {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                }
            } else {
                Text("图片已丢失").foregroundStyle(.secondary)
            }
        }
        .navigationTitle(record.modeName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(role: .destructive) {
                    store.delete(record)
                } label: {
                    Image(systemName: "trash")
                }
            }
        }
    }
}

/// SSTV 设置页。
struct SstvSettingsView: View {

    @ObservedObject var store = SstvStore.shared

    var body: some View {
        Form {
            Section("接收") {
                Toggle("解码后自动保存到图库", isOn: $store.autoSave)
            }
            Section {
                Text("iOS 端只支持前台接收；锁屏/后台持续的音频采集受系统限制，无法保证像 Android 前台服务那样连续工作。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("SSTV 设置")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// SSTV 发射页（待补图片编码）。
struct SstvTxView: View {
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "photo.on.rectangle")
                .font(.system(size: 40))
                .foregroundStyle(.secondary)
            Text("SSTV 发射")
                .font(.title3.weight(.semibold))
            Text("共享内核已具备编码器；iOS 端还需接入相册选图与编码播放，将在后续提交补齐。")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .navigationTitle("SSTV 发射")
        .navigationBarTitleDisplayMode(.inline)
    }
}
