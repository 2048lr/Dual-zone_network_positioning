import SwiftUI

/// 音频/数据类功能的占位子页：对应 Android 的 aprs / ft8 / sstv / cw / qso 各 screen。
/// 页面结构和 Android 同名路由一致，但功能体依赖 iOS 原生音频/网络栈，逐个移植。
/// 每页都写清「Android 有什么 / iOS 卡在哪」，便于后续逐个替换成真实现。

// MARK: - APRS

struct AprsMainView: View {
    var body: some View {
        List {
            Section {
                NavigationLink(value: Route.aprsStations) { Label("站点列表", systemImage: "antenna.radiowaves.left.and.right") }
                NavigationLink(value: Route.aprsMessages) { Label("消息", systemImage: "message") }
                NavigationLink(value: Route.aprsMap) { Label("地图", systemImage: "map") }
                NavigationLink(value: Route.aprsSettings) { Label("设置", systemImage: "gearshape") }
            } footer: {
                Text("Android 端 APRS 支持位置上报与 iGate 接收；iOS 端网络协议层待移植。")
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("APRS")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct AprsSettingsView: View {
    var body: some View {
        Form {
            Section("呼号") {
                TextField("呼号", text: .constant(""))
            }
            Section("iGate") {
                TextField("服务器", text: .constant(""))
                TextField("端口", text: .constant(""))
            }
            Section {
                Text("APRS 设置尚未生效，功能开发中。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("APRS 设置")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct AprsStationsView: View {
    var body: some View {
        List {
            Text("尚未连接 iGate，暂无站点。").foregroundStyle(.secondary)
        }
        .navigationTitle("站点列表")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct AprsMessagesView: View {
    var body: some View {
        List {
            Text("APRS 消息功能开发中。").foregroundStyle(.secondary)
        }
        .navigationTitle("消息")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct AprsMapView: View {
    var body: some View {
        VStack {
            Image(systemName: "map")
                .font(.system(size: 40)).foregroundStyle(.secondary)
            Text("APRS 地图开发中（将使用 MapKit）。")
                .font(.footnote).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .navigationTitle("APRS 地图")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct AprsSymbolPickerView: View {
    var body: some View {
        List {
            Text("符号选择器开发中。").foregroundStyle(.secondary)
        }
        .navigationTitle("符号选择")
        .navigationBarTitleDisplayMode(.inline)
    }
}

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

struct QsoLogListView: View {
    var body: some View {
        List {
            Text("暂无通联记录；QSO 日志（含 ADIF 导出）开发中。")
                .foregroundStyle(.secondary)
        }
        .navigationTitle("QSO 日志")
        .navigationBarTitleDisplayMode(.inline)
    }
}
