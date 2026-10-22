import SwiftUI

/// 音频/数据类功能的占位子页：对应 Android 的 aprs / ft8 / sstv / cw / qso 各 screen。
/// 页面结构和 Android 同名路由一致，但功能体依赖 iOS 原生音频/网络栈，逐个移植。
/// 每页都写清「Android 有什么 / iOS 卡在哪」，便于后续逐个替换成真实现。

// MARK: - FT8

struct Ft8MainView: View {
    var body: some View {
        List {
            Section {
                NavigationLink(value: Route.ft8Settings) { Label("设置", systemImage: "gearshape") }
            } footer: {
                Text("FT8 编解码已在共享内核实现；iOS 需要原生音频采集/播放（AudioEngine），正在移植。")
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("FT8")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct Ft8SettingsView: View {
    var body: some View {
        Form {
            Section("电台") {
                TextField("频率（Hz）", text: .constant(""))
            }
            Section {
                Text("FT8 设置尚未生效。").font(.footnote).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("FT8 设置")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - SSTV

struct SstvMainView: View {
    let presetModeName: String?
    let autoStart: Bool

    var body: some View {
        List {
            Section {
                NavigationLink(value: Route.sstvGallery(pickForTx: false)) { Label("图库", systemImage: "photo.on.rectangle") }
                NavigationLink(value: Route.sstvTx) { Label("发射", systemImage: "waveform") }
                NavigationLink(value: Route.sstvSettings) { Label("设置", systemImage: "gearshape") }
            } header: {
                if let presetModeName { Text("锁定模式：\(presetModeName)\(autoStart ? " · 自动开始" : "")") }
            } footer: {
                Text("SSTV 解码依赖跨帧音频采集，iOS 后台音频链路正在移植。")
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("SSTV")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct SstvSettingsView: View {
    var body: some View {
        Form {
            Section("接收") {
                Toggle("VIS 自动识别", isOn: .constant(true))
            }
            Section {
                Text("SSTV 设置尚未生效。").font(.footnote).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("SSTV 设置")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct SstvGalleryView: View {
    let pickForTx: Bool
    var body: some View {
        List {
            Text(pickForTx ? "选择要发射的图（图库为空）" : "SSTV 图库为空；接收功能开发中。")
                .foregroundStyle(.secondary)
        }
        .navigationTitle("SSTV 图库")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct SstvTxView: View {
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "waveform.path").font(.system(size: 40)).foregroundStyle(.secondary)
            Text("SSTV 发射开发中：选图 → 编码 → 播放/导出 WAV。")
                .font(.footnote).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .navigationTitle("SSTV 发射")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - HF Fax

struct HfFaxView: View {
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "printer").font(.system(size: 40)).foregroundStyle(.secondary)
            Text("HF 传真（WEFAX）接收开发中。")
                .font(.footnote).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .navigationTitle("HF 传真")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - CW
// MARK: - QSO 日志

