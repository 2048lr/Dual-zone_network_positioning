import SwiftUI
import Shared

/// 卫星列表页：CelesTrak amateur 分组通常一百多颗，支持按名称 / NORAD 搜索，
/// 收藏的卫星排在最前，行尾可直接加星。
struct SatelliteListView: View {

    let satellites: [TleRecord]
    let selectedNorad: Int32?
    let favorites: Set<Int32>
    let onSelect: (TleRecord) -> Void
    let onToggleFavorite: (Int32) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    private var filtered: [TleRecord] {
        let keyword = query.trimmingCharacters(in: .whitespaces)
        let matched: [TleRecord]
        if keyword.isEmpty {
            matched = satellites
        } else {
            matched = satellites.filter {
                $0.name.localizedCaseInsensitiveContains(keyword)
                    || String($0.norad).contains(keyword)
            }
        }
        return matched.sorted { lhs, rhs in
            let lhsFavorite = favorites.contains(lhs.norad)
            let rhsFavorite = favorites.contains(rhs.norad)
            if lhsFavorite != rhsFavorite { return lhsFavorite }
            return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
        }
    }

    var body: some View {
        List(filtered, id: \.norad) { record in
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(record.name)
                    Text("NORAD \(record.norad)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if record.norad == selectedNorad {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(Color.accentColor)
                }
                Button {
                    onToggleFavorite(record.norad)
                } label: {
                    Image(systemName: favorites.contains(record.norad) ? "star.fill" : "star")
                        .foregroundStyle(favorites.contains(record.norad) ? Color.yellow : Color.secondary)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(favorites.contains(record.norad) ? "取消收藏" : "收藏")
            }
            .contentShape(Rectangle())
            .onTapGesture {
                onSelect(record)
                dismiss()
            }
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
