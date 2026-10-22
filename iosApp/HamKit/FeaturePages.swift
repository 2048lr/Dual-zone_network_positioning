import SwiftUI

/// 音频/数据类功能的占位子页：对应 Android 的 aprs / ft8 / sstv / cw / qso 各 screen。
/// 页面结构和 Android 同名路由一致，但功能体依赖 iOS 原生音频/网络栈，逐个移植。
/// 每页都写清「Android 有什么 / iOS 卡在哪」，便于后续逐个替换成真实现。

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

