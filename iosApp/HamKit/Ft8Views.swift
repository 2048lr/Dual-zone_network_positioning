import SwiftUI
import Shared

/// FT8 主页面：对应 Android `Ft8Main` —— 设置摘要、消息编辑、发射/接收、解码结果。
struct Ft8MainView: View {

    @ObservedObject var store = Ft8Store.shared
    @StateObject private var audio = Ft8Audio()

    @State private var message = ""
    @State private var decoded: [Ft8DecodedMessage] = []
    @State private var status = "空闲"
    @State private var qsoSeed: Ft8DecodedMessage?

    var body: some View {
        List {
            Section("台站") {
                LabeledContent("呼号", value: store.callsign.isEmpty ? "未设置" : store.callsign)
                LabeledContent("网格", value: store.grid.isEmpty ? "未设置" : store.grid)
                LabeledContent("频段", value: "\(store.band) · \(String(format: "%.3f MHz", Double(store.bandFreqHz) / 1_000_000))")
                NavigationLink(value: Route.ft8Settings) {
                    Label("FT8 设置", systemImage: "gearshape")
                }
            }

            Section("消息") {
                TextField("如 CQ BH4XYZ OM44", text: $message)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                Button("生成 CQ") {
                    message = "CQ \(store.callsign) \(store.grid)".trimmingCharacters(in: .whitespaces)
                }
                .disabled(store.callsign.isEmpty)
                Text(status)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section("发射 / 接收") {
                Button(audio.isPlaying ? "停止发射" : "发射（15 秒）") {
                    if audio.isPlaying {
                        audio.stopPlayback()
                        status = "已停止"
                    } else {
                        transmit()
                    }
                }
                .disabled(message.isEmpty)

                Button(audio.isRecording ? "正在接收…" : "接收（录音 15 秒）") {
                    if audio.isRecording {
                        audio.cancelRecording()
                        status = "已取消接收"
                    } else {
                        receive()
                    }
                }
                .disabled(audio.isRecording)
            }

            Section("解码结果") {
                if decoded.isEmpty {
                    Text("还没有解码结果").foregroundStyle(.secondary)
                }
                ForEach(Array(decoded.enumerated()), id: \.offset) { _, item in
                    VStack(alignment: .leading, spacing: 3) {
                        Text(item.message).font(.body.monospaced())
                        Text(String(format: "SNR %d dB · DT %.2f s · %.0f Hz", item.snr, item.dtSeconds, item.freqHz))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                        Button("记入 QSO 日志") {
                            qsoSeed = item
                        }
                        .font(.caption)
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("FT8")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: Binding(
            get: { qsoSeed.map { QsoSeed(message: $0) } },
            set: { if $0 == nil { qsoSeed = nil } }
        )) { seed in
            NavigationStack {
                QsoEditView(entry: makeQso(from: seed.message))
            }
        }
        .onDisappear {
            audio.stopPlayback()
            audio.cancelRecording()
        }
    }

    private func transmit() {
        let pcmBytes = Ft8Bridge.shared.encodeToPcm(
            message: message,
            callsign: store.callsign,
            grid: store.grid
        )
        guard pcmBytes.size > 0 else {
            status = "消息无法打包（检查呼号/网格格式）"
            return
        }
        var data = Data(count: Int(pcmBytes.size))
        for i in 0..<Int(pcmBytes.size) {
            data[i] = UInt8(bitPattern: pcmBytes.get(index: Int32(i)))
        }
        status = "正在发射…"
        audio.play(pcm: data)
    }

    private func receive() {
        status = "正在接收 15 秒…"
        audio.record(seconds: 15) { data in
            let bytes = KotlinByteArray(size: Int32(data.count))
            for (index, byte) in data.enumerated() {
                bytes.set(index: Int32(index), value: Int8(bitPattern: byte))
            }
            let result = Ft8Bridge.shared.decodePcm(bytes: bytes)
            DispatchQueue.main.async {
                if let result {
                    decoded.insert(result, at: 0)
                    status = "解码成功：\(result.message)"
                } else {
                    status = "本时隙没有解出消息"
                }
            }
        }
    }

    private func makeQso(from text: String) -> QsoEntry {
        // FT8 文本常见形式："CQ CALL GRID" / "CALL1 CALL2 REPORT"
        let parts = text.split(separator: " ").map(String.init)
        let call = parts.first(where: { $0 != "CQ" && $0 != store.callsign }) ?? ""
        return QsoEntry(
            callsign: call,
            grid: parts.last?.count == 4 ? (parts.last ?? "") : "",
            reportSent: "-99",
            reportReceived: "-99",
            bandName: store.band,
            freqHz: store.bandFreqHz,
            mode: "FT8",
            qsoTime: Date(),
            isComplete: false,
            operatorCallsign: store.callsign,
            myGrid: store.grid,
            comment: text
        )
    }
}

private struct QsoSeed: Identifiable {
    let message: Ft8DecodedMessage
    var id: String { message.message }
}

/// FT8 设置页。
struct Ft8SettingsView: View {

    @ObservedObject var store = Ft8Store.shared

    var body: some View {
        Form {
            Section("台站") {
                TextField("呼号", text: $store.callsign)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                TextField("网格（如 OM44）", text: $store.grid)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
            }
            Section("频段") {
                Picker("频段", selection: $store.band) {
                    ForEach(Ft8Store.bands) { band in
                        Text("\(band.id) · \(String(format: "%.3f MHz", Double(band.freqHz) / 1_000_000))")
                            .tag(band.id)
                    }
                }
            }
            Section {
                Text("iOS 端只做音频收发与编解码；自动通联状态机（CQ/应答/73）尚未移植，当前为手动发消息 + 解码后手动记入 QSO。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("FT8 设置")
        .navigationBarTitleDisplayMode(.inline)
    }
}
