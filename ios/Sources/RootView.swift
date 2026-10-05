import SwiftUI

/// 与 Android 版对齐的五个顶层入口；此版本还是空壳，先用 CI 证明工程能编译、能跑测试。
enum TopDestination: String, CaseIterable, Identifiable {
    case library = "文件库"
    case folder = "目录"
    case search = "搜索"
    case trash = "回收站"
    case settings = "设置"

    var id: String { rawValue }

    var symbol: String {
        switch self {
        case .library: "square.stack.3d.up"
        case .folder: "folder"
        case .search: "magnifyingglass"
        case .trash: "trash"
        case .settings: "gearshape"
        }
    }
}

struct RootView: View {
    var body: some View {
        TabView {
            ForEach(TopDestination.allCases) { destination in
                VStack(spacing: 10) {
                    Image(systemName: destination.symbol)
                        .font(.system(size: 34, weight: .light))
                        .foregroundStyle(.secondary)
                    Text(destination.rawValue)
                        .font(.title3)
                    Text("占位骨架：等待数据层与导入流程接入")
                        .font(.footnote)
                        .foregroundStyle(.tertiary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .tabItem { Label(destination.rawValue, systemImage: destination.symbol) }
                .tag(destination)
            }
        }
    }
}

#Preview {
    RootView()
}
