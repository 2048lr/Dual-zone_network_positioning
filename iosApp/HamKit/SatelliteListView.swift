import SwiftUI
import Shared

/// 卫星列表页：CelesTrak amateur 分组通常一百多颗，支持按名称 / NORAD 搜索。
///
/// 选中后回调给 `TrackerModel`，由它重新计算位置与过境。
struct SatelliteListView: View {

    let satellites: [TleRecord]
    let selectedNorad: Int32?
    let onSelect: (TleRecord) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    private var filtered: [TleRecord] {
        guard !query.trimmingCharacters(in: .whitespaces).isEmpty else { return satellites }
        return satellites.filter {
            $0.name.localizedCaseInsensitiveContains(query)
                || String($0.norad).contains(query)
        }
    }

    var body: some View {
        List(filtered, id: \.norad) { record in
            Button {
                onSelect(record)
                dismiss()
            } label: {
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(record.name).foregroundStyle(.primary)
                        Text("NORAD \(record.norad)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    if record.norad == selectedNorad {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundStyle(Color.accentColor)
                    }
                }
            }
            .buttonStyle(.plain)
        }
        .listStyle(.plain)
        .searchable(text: $query, prompt: "搜索名称或 NORAD")
        .navigationTitle("卫星列表")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Text("\(filtered.count) / \(satellites.count)")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }
}
